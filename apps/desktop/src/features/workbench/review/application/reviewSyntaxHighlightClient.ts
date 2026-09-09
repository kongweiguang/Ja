// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SyntaxToken } from "@/shared/syntax";

export interface ReviewSyntaxFragment {
  readonly lineIndexes: readonly number[];
  readonly lines: readonly string[];
}

export interface ReviewSyntaxRequest {
  readonly identity: string;
  readonly filePath: string;
  readonly lineCount: number;
  readonly fragments: readonly ReviewSyntaxFragment[];
}

export type ReviewSyntaxResult = readonly (readonly SyntaxToken[] | undefined)[];

interface WorkerRequest extends ReviewSyntaxRequest {
  readonly requestId: number;
}

interface WorkerReply {
  readonly requestId: number;
  readonly lines?: ReviewSyntaxResult;
  readonly error?: "highlight_failed";
}

interface PendingRequest {
  readonly input: ReviewSyntaxRequest;
  readonly promise: Promise<ReviewSyntaxResult | undefined>;
  readonly resolve: (result: ReviewSyntaxResult | undefined) => void;
  readonly reject: (error: Error) => void;
  requestId?: number;
}

export interface ReviewSyntaxHighlighter {
  highlight(input: ReviewSyntaxRequest): Promise<ReviewSyntaxResult | undefined>;
  dispose(): void;
}

const MAX_HIGHLIGHT_LINES = 10_000;
const MAX_HIGHLIGHT_FRAGMENTS = 20_000;
const MAX_HIGHLIGHT_CHARACTERS = 512 * 1024;
const SYNTAX_ROLES = new Set([
  "comment",
  "keyword",
  "symbol",
  "type",
  "property",
  "attribute",
  "string",
  "number",
  "url",
]);

/** 被更新目标替换属于正常导航，不应转换成用户可见的高亮错误。 */
export class SupersededReviewSyntaxRequest extends Error {
  /** 固定错误类型只用于内部调度识别，不携带源码或文件身份。 */
  constructor() {
    super("review syntax request superseded");
    this.name = "SupersededReviewSyntaxRequest";
  }
}

/** 超过 Review 展示预算的正文保持完整纯文本，不把无收益的大消息复制到 Worker。 */
function requestWithinHighlightBudget(input: ReviewSyntaxRequest): boolean {
  if (
    input.lineCount < 0 ||
    input.lineCount > MAX_HIGHLIGHT_LINES ||
    input.fragments.length > MAX_HIGHLIGHT_FRAGMENTS
  )
    return false;
  let characters = 0;
  for (const fragment of input.fragments) {
    if (fragment.lineIndexes.length !== fragment.lines.length) return false;
    for (const line of fragment.lines) {
      characters += line.length + 1;
      if (characters > MAX_HIGHLIGHT_CHARACTERS) return false;
    }
  }
  return true;
}

/**
 * Worker 响应既校验形状也校验每行文本守恒；token 只能增加颜色角色，不能替换、遗漏或注入
 * Review 正文。role 使用闭集，避免任意字符串进入 data attribute 与 CSS variable。
 */
function validWorkerResult(input: ReviewSyntaxRequest, result: ReviewSyntaxResult): boolean {
  if (!Array.isArray(result) || result.length !== input.lineCount) return false;
  const sourceByLine = new Map<number, string>();
  for (const fragment of input.fragments) {
    for (const [index, lineIndex] of fragment.lineIndexes.entries()) {
      const line = fragment.lines[index];
      if (lineIndex >= 0 && lineIndex < input.lineCount && line !== undefined)
        sourceByLine.set(lineIndex, line);
    }
  }
  return result.every((tokens, lineIndex) => {
    if (tokens === undefined) return true;
    if (!Array.isArray(tokens) || sourceByLine.get(lineIndex) === undefined) return false;
    return (
      tokens.every(
        (token) =>
          token !== null &&
          typeof token === "object" &&
          typeof token.text === "string" &&
          (token.role === undefined || SYNTAX_ROLES.has(token.role)),
      ) && tokens.map((token) => token.text).join("") === sourceByLine.get(lineIndex)
    );
  });
}

/**
 * 单个可见 Diff 仅拥有一个 module Worker。调度严格限制为 1 active + 1 latest；快速切换时
 * 尚未发送的中间目标立即淘汰，已开始的解析只能迟到丢弃，绝不形成无界 Worker 消息队列。
 */
export function createReviewSyntaxHighlighter(): ReviewSyntaxHighlighter {
  if (typeof Worker === "undefined") {
    return {
      /** 无 Worker 的测试或 SSR 环境保持完整纯文本，不在主线程偷偷执行 parser。 */
      highlight: async () => undefined,
      dispose: () => undefined,
    };
  }
  const worker = new Worker(new URL("./reviewSyntaxHighlightWorker.ts", import.meta.url), {
    type: "module",
  });
  let nextRequestId = 1;
  let active: PendingRequest | undefined;
  let latest: PendingRequest | undefined;
  let disposed = false;
  let workerFailure: Error | undefined;
  let workerTerminated = false;

  /** Worker 终止保持幂等，错误收口后组件 cleanup 不会再次调用底层 terminate。 */
  const terminateWorker = (): void => {
    if (workerTerminated) return;
    workerTerminated = true;
    worker.terminate();
  };

  /** 只把当前 active 发给 Worker；latest 只有在 active 收口后才有资格进入 Worker。 */
  const start = (request: PendingRequest): void => {
    if (disposed) {
      request.reject(new Error("review syntax highlighter disposed"));
      return;
    }
    request.requestId = nextRequestId++;
    active = request;
    worker.postMessage({ ...request.input, requestId: request.requestId } satisfies WorkerRequest);
  };

  /** active 完成后原子接管唯一 latest，避免 receive 与新 highlight 交错丢失最终目标。 */
  const startLatest = (): void => {
    active = undefined;
    const queued = latest;
    latest = undefined;
    if (queued !== undefined) start(queued);
  };

  /** 响应必须命中当前活动 request id；任何迟到、重复或畸形消息都不改变可见内容。 */
  const receive = (event: MessageEvent<WorkerReply>): void => {
    const request = active;
    if (request === undefined || request.requestId !== event.data.requestId) return;
    if (
      event.data.error !== undefined ||
      event.data.lines === undefined ||
      !validWorkerResult(request.input, event.data.lines)
    ) {
      startLatest();
      request.reject(new Error("review syntax highlight failed"));
    } else {
      startLatest();
      request.resolve(event.data.lines);
    }
  };
  /** 加载、执行或结构化克隆失败会终止整条 lane，避免 active Promise 永久占住 latest。 */
  const failWorker = (): void => {
    if (disposed || workerFailure !== undefined) return;
    workerFailure = new Error("review syntax worker unavailable");
    active?.reject(workerFailure);
    latest?.reject(workerFailure);
    active = undefined;
    latest = undefined;
    terminateWorker();
  };
  worker.addEventListener("message", receive);
  worker.addEventListener("error", failWorker);
  worker.addEventListener("messageerror", failWorker);

  return {
    /** 相同在途 identity 复用 Promise；完成结果不缓存，新的导航只保留最后一个未发送目标。 */
    highlight: (input) => {
      if (disposed) return Promise.reject(new Error("review syntax highlighter disposed"));
      if (workerFailure !== undefined) return Promise.reject(workerFailure);
      if (!requestWithinHighlightBudget(input)) {
        if (latest !== undefined) {
          latest.reject(new SupersededReviewSyntaxRequest());
          latest = undefined;
        }
        return Promise.resolve(undefined);
      }
      if (active?.input.identity === input.identity) {
        if (latest !== undefined) {
          latest.reject(new SupersededReviewSyntaxRequest());
          latest = undefined;
        }
        return active.promise;
      }
      if (latest?.input.identity === input.identity) return latest.promise;

      let resolve!: (result: ReviewSyntaxResult | undefined) => void;
      let reject!: (error: Error) => void;
      const promise = new Promise<ReviewSyntaxResult | undefined>((nextResolve, nextReject) => {
        resolve = nextResolve;
        reject = nextReject;
      });
      const request: PendingRequest = { input, promise, resolve, reject };
      if (active === undefined) start(request);
      else {
        latest?.reject(new SupersededReviewSyntaxRequest());
        latest = request;
      }
      return promise;
    },
    /** 隐藏或卸载时终止 Worker，并拒绝至多两个回调。 */
    dispose: () => {
      if (disposed) return;
      disposed = true;
      worker.removeEventListener("message", receive);
      worker.removeEventListener("error", failWorker);
      worker.removeEventListener("messageerror", failWorker);
      terminateWorker();
      active?.reject(new Error("review syntax highlighter disposed"));
      latest?.reject(new Error("review syntax highlighter disposed"));
      active = undefined;
      latest = undefined;
    },
  };
}
