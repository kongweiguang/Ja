// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ParsedTurnReviewFile } from "../domain/turnReviewDiff";

interface WorkerReply {
  readonly requestId: number;
  readonly files?: ParsedTurnReviewFile[];
  readonly error?: "invalid_diff";
}

export interface TurnReviewDiffParser {
  parse(unified: string): Promise<readonly ParsedTurnReviewFile[]>;
  cancelQueued(): void;
  dispose(): void;
}

interface ParseRequest {
  readonly requestId: number;
  readonly unified: string;
  readonly resolve: (files: readonly ParsedTurnReviewFile[]) => void;
  readonly reject: (error: Error) => void;
}

/**
 * 一个 Worker 只执行一个解析，另保留最新待解析正文。读取快于解析时也不能向 Worker
 * 无限投递过时文件；dispose 拒绝所有等待者并立即释放正文。
 */
export function createTurnReviewDiffParser(): TurnReviewDiffParser {
  if (typeof Worker === "undefined") {
    return {
      /** 非浏览器测试环境仍复用同一严格 parser，生产 WebView 始终走 Worker。 */
      parse: async (unified) =>
        (await import("../domain/turnReviewDiff")).parseTurnReviewDiff(unified),
      cancelQueued: () => undefined,
      dispose: () => undefined,
    };
  }
  const worker = new Worker(new URL("./turnReviewDiffWorker.ts", import.meta.url), {
    type: "module",
  });
  let nextRequestId = 1;
  let disposed = false;
  let failure: Error | undefined;
  let active: ParseRequest | undefined;
  let latest: ParseRequest | undefined;
  /** 只有 Worker 空闲时才复制正文，避免消息通道内保留无法撤回的旧文件队列。 */
  const start = (request: ParseRequest): void => {
    active = request;
    try {
      worker.postMessage({ requestId: request.requestId, unified: request.unified });
    } catch {
      fail();
    }
  };
  /** 只接纳与活动请求匹配的封闭响应，重复或迟到消息保持无副作用。 */
  const receive = (event: MessageEvent<WorkerReply>): void => {
    const request = active;
    if (request === undefined || request.requestId !== event.data.requestId) return;
    active = undefined;
    if (event.data.error !== undefined || event.data.files === undefined)
      request.reject(new Error("invalid diff"));
    else request.resolve(event.data.files);
    const queued = latest;
    latest = undefined;
    if (queued !== undefined) start(queued);
  };
  /** Worker 启动或消息解码失败必须结算等待者，不能让正文读取永久停留在 loading。 */
  const fail = (): void => {
    if (disposed || failure !== undefined) return;
    failure = new Error("diff parser unavailable");
    active?.reject(failure);
    latest?.reject(failure);
    active = undefined;
    latest = undefined;
  };
  worker.addEventListener("message", receive);
  worker.addEventListener("error", fail);
  worker.addEventListener("messageerror", fail);
  return {
    /** 新文件尚未读完时就撤销旧待解析正文，不让新选择多等一次无用解析。 */
    cancelQueued: () => {
      const error = new Error("diff parsing superseded");
      error.name = "AbortError";
      latest?.reject(error);
      latest = undefined;
    },
    /** 将原始正文转移给 Worker 解析，UI 线程只接收有界结构化行。 */
    parse: (unified) =>
      new Promise((resolve, reject) => {
        if (disposed || failure !== undefined) {
          reject(failure ?? new Error("diff parser disposed"));
          return;
        }
        const requestId = nextRequestId++;
        const request = { requestId, unified, resolve, reject };
        if (active === undefined) start(request);
        else {
          const superseded = new Error("diff parsing superseded");
          superseded.name = "AbortError";
          latest?.reject(superseded);
          latest = request;
        }
      }),
    /** 终止 Worker 并拒绝所有等待者，确保切换 target 时不会泄漏旧正文。 */
    dispose: () => {
      if (disposed) return;
      disposed = true;
      worker.removeEventListener("message", receive);
      worker.removeEventListener("error", fail);
      worker.removeEventListener("messageerror", fail);
      worker.terminate();
      const error = new Error("diff parser disposed");
      active?.reject(error);
      latest?.reject(error);
      active = undefined;
      latest = undefined;
    },
  };
}
