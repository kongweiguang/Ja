// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";

interface WorkerHarness {
  readonly dispatch: (request: unknown) => void;
  readonly postMessage: ReturnType<typeof vi.fn>;
}

/** 在 jsdom 中捕获专用 Worker listener，验证真实 Files parser 的片段隔离与预算回退。 */
async function loadWorker(): Promise<WorkerHarness> {
  vi.resetModules();
  let listener: ((event: MessageEvent<unknown>) => void) | undefined;
  const postMessage = vi.fn();
  vi.stubGlobal("self", {
    postMessage,
    addEventListener: vi.fn((type: string, next: (event: MessageEvent<unknown>) => void) => {
      if (type === "message") listener = next;
    }),
  });
  await import("@/features/workbench/review/application/reviewSyntaxHighlightWorker");
  if (listener === undefined) throw new Error("worker listener unavailable");
  return {
    dispatch: (request) => listener!({ data: request } as MessageEvent),
    postMessage,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("review syntax highlight worker", () => {
  it("按 hunk 两侧独立保留多行注释语境", async () => {
    const worker = await loadWorker();
    worker.dispatch({
      requestId: 7,
      filePath: "main.ts",
      lineCount: 5,
      fragments: [
        { lineIndexes: [0, 1], lines: ["/* old", "old */"] },
        { lineIndexes: [2], lines: ['const value = "new";'] },
        { lineIndexes: [3, 4], lines: ["/* second", "second */"] },
      ],
    });

    const reply = worker.postMessage.mock.calls[0]?.[0] as {
      lines: Array<Array<{ text: string; role?: string }> | undefined>;
    };
    expect(reply.lines[1]).toEqual([{ text: "old */", role: "comment" }]);
    expect(reply.lines[2]?.some((token) => token.role === "keyword")).toBe(true);
    expect(reply.lines[4]).toEqual([{ text: "second */", role: "comment" }]);
  });

  it("超过共享字符预算时返回完整索引形状并让 UI 回退纯文本", async () => {
    const worker = await loadWorker();
    const huge = "x".repeat(256 * 1024 + 1);
    worker.dispatch({
      requestId: 8,
      filePath: "large.ts",
      lineCount: 1,
      fragments: [{ lineIndexes: [0], lines: [huge] }],
    });

    expect(worker.postMessage).toHaveBeenCalledWith({
      requestId: 8,
      lines: [undefined],
    });
  });
});
