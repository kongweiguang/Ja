// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createTurnReviewDiffParser, type TurnReviewDiffParser } from "./turnReviewDiffClient";
import type { ParsedTurnReviewFile } from "../domain/turnReviewDiff";

interface TurnReviewReadInput {
  readonly key: string;
  readonly load: (signal: AbortSignal) => Promise<string>;
}

interface TransportRead {
  readonly owner: symbol;
  readonly input: TurnReviewReadInput;
  readonly controller: AbortController;
  readonly promise: Promise<string>;
  readonly resolve: (content: string) => void;
  readonly reject: (error: unknown) => void;
}

export interface TurnReviewReadClient {
  read(input: TurnReviewReadInput): Promise<readonly ParsedTurnReviewFile[]>;
  cancel(): void;
  dispose(): void;
}

const MAX_IN_FLIGHT_READS = 2;
const activeTransports = new Set<TransportRead>();
let latestTransport: TransportRead | undefined;

/** 被更新选择替换是预期导航结果，调用方应静默拒绝而不是展示读取失败。 */
export class SupersededTurnReviewRead extends Error {
  /** 固定错误类型只用于内部调度识别，不携带 artifact、路径或正文。 */
  constructor() {
    super("turn review read superseded");
    this.name = "SupersededTurnReviewRead";
  }
}

/** 底层完成后才释放全窗口 permit，隐藏后立即重开也不会突破两个真实在途请求。 */
function startTransport(request: TransportRead): void {
  activeTransports.add(request);
  void Promise.resolve()
    .then(() => {
      request.controller.signal.throwIfAborted();
      return request.input.load(request.controller.signal);
    })
    .then(request.resolve, request.reject)
    .finally(() => {
      activeTransports.delete(request);
      if (latestTransport === undefined || activeTransports.size >= MAX_IN_FLIGHT_READS) return;
      const queued = latestTransport;
      latestTransport = undefined;
      startTransport(queued);
    });
}

/** 每个 owner 只复用自己仍有效的在途请求，跨面板生命周期不共享正文。 */
function scheduleTransport(owner: symbol, input: TurnReviewReadInput): TransportRead {
  const active = [...activeTransports].find(
    (request) =>
      request.owner === owner &&
      request.input.key === input.key &&
      !request.controller.signal.aborted,
  );
  if (active !== undefined) return active;
  if (
    latestTransport?.owner === owner &&
    latestTransport.input.key === input.key &&
    !latestTransport.controller.signal.aborted
  )
    return latestTransport;

  let resolve!: (content: string) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<string>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  const request: TransportRead = {
    owner,
    input,
    controller: new AbortController(),
    promise,
    resolve,
    reject,
  };
  if (activeTransports.size < MAX_IN_FLIGHT_READS) startTransport(request);
  else {
    latestTransport?.controller.abort();
    latestTransport?.reject(new SupersededTurnReviewRead());
    latestTransport = request;
  }
  return request;
}

/** owner 改选或隐藏时使旧请求停止后续解析；已进入 IPC 的工作仍计入 permit 直到真实收口。 */
function cancelOwner(owner: symbol): void {
  for (const request of activeTransports) {
    if (request.owner === owner) request.controller.abort();
  }
  if (latestTransport?.owner === owner) {
    latestTransport.controller.abort();
    latestTransport.reject(new SupersededTurnReviewRead());
    latestTransport = undefined;
  }
}

/**
 * 冻结正文不做完成结果缓存；全窗口最多两个已发读取和一个最新待发选择。Parser 仍归属于
 * 当前可见面板，隐藏即释放，迟到传输不能再进入 Worker。
 */
export function createTurnReviewReadClient(
  parser: TurnReviewDiffParser = createTurnReviewDiffParser(),
): TurnReviewReadClient {
  const owner = Symbol("turn-review-reader");
  let currentKey: string | undefined;
  let currentPromise: Promise<readonly ParsedTurnReviewFile[]> | undefined;
  let disposed = false;
  let generation = 0;

  /** 连无正文文件的选择也撤销上次待解析项；在途传输仍持有全窗口 permit。 */
  const cancel = (): void => {
    generation += 1;
    currentKey = undefined;
    currentPromise = undefined;
    cancelOwner(owner);
    parser.cancelQueued();
  };

  return {
    cancel,
    /** 已完成文件再次选择必定重读；只有当前同键 effect 重放才合并仍在途 Promise。 */
    read: (input) => {
      if (disposed) return Promise.reject(new Error("turn review reader disposed"));
      if (currentKey === input.key && currentPromise !== undefined) return currentPromise;
      cancel();
      currentKey = input.key;
      const requestGeneration = generation;
      const transport = scheduleTransport(owner, input);
      const parsed = transport.promise
        .then((content) => {
          transport.controller.signal.throwIfAborted();
          if (disposed || generation !== requestGeneration) throw new SupersededTurnReviewRead();
          return parser.parse(content);
        })
        .then((files) => {
          if (disposed || generation !== requestGeneration) throw new SupersededTurnReviewRead();
          return files;
        });
      currentPromise = parsed;
      /** 两个终态都清理引用，不创建无人接收的 rejected finally Promise。 */
      const clearCurrent = (): void => {
        if (currentPromise === parsed) currentPromise = undefined;
      };
      void parsed.then(clearCurrent, clearCurrent);
      return parsed;
    },
    /** 隐藏或卸载时撤销待发项并释放 Worker；迟到 IPC 只结算全窗口 permit。 */
    dispose: () => {
      if (disposed) return;
      disposed = true;
      cancel();
      parser.dispose();
    },
  };
}
