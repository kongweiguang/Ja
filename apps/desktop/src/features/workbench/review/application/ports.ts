// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ReviewAction,
  ReviewApplyResult,
  ReviewCancelResult,
  ReviewCatalog,
  ReviewFileDiff,
  ReviewInvalidatedEvent,
  ReviewSnapshot,
  ReviewSource,
  ReviewTarget,
} from "../domain/types";

interface ReviewCatalogInput {
  workspaceId: string;
  maxCommits?: number;
}

interface ReviewSnapshotInput {
  workspaceId: string;
  source: ReviewSource;
}

interface ReviewFileDiffInput {
  workspaceId: string;
  source: ReviewSource;
  revision: string;
  fileId: string;
}

interface ReviewApplyInput {
  workspaceId: string;
  source: ReviewSource;
  revision: string;
  action: ReviewAction;
  target: ReviewTarget;
  operationId: string;
}

interface ReviewCancelInput {
  workspaceId: string;
  operationId: string;
}

type ReviewUnsubscribe = () => void | Promise<void>;

/** application 只依赖该窄端口，Tauri adapter 由 App 组合并注入。 */
export interface ReviewPort {
  catalog(input: ReviewCatalogInput): Promise<ReviewCatalog>;
  snapshot(input: ReviewSnapshotInput): Promise<ReviewSnapshot>;
  fileDiff(input: ReviewFileDiffInput): Promise<ReviewFileDiff>;
  apply(input: ReviewApplyInput): Promise<ReviewApplyResult>;
  cancel(input: ReviewCancelInput): Promise<ReviewCancelResult>;
  subscribeInvalidated(
    listener: (event: ReviewInvalidatedEvent) => void,
  ): Promise<ReviewUnsubscribe>;
}

export interface ReviewFailure {
  code: string;
  message: string;
  retryable: boolean;
}

const REVIEW_FAILURES: Readonly<Record<string, Omit<ReviewFailure, "code">>> = {
  REVIEW_STALE: { message: "工作区已经变化，请重新读取 Review。", retryable: true },
  REVIEW_CONFLICT: { message: "当前文件存在重叠修改，无法安全应用。", retryable: false },
  REVIEW_READ_ONLY: { message: "此 Review 来源为只读。", retryable: false },
  REVIEW_UNAVAILABLE: { message: "当前轮次没有可精确归因的变更。", retryable: false },
  REVIEW_LIMIT: { message: "Review 内容超过安全上限。", retryable: false },
  NOT_GIT_REPOSITORY: { message: "当前目录不是 Git 工作区，审查不可用。", retryable: false },
  INVALID_INPUT: { message: "Review 请求参数无效。", retryable: false },
  WORKSPACE_ESCAPE: { message: "请求路径不在工作区内。", retryable: false },
  HOST_BUSY: { message: "工作区正被其他操作占用，请稍后重试。", retryable: true },
  CANCELLED: { message: "Review 操作已取消。", retryable: true },
  RUNTIME_UNAVAILABLE: { message: "运行时暂不可用，请稍后重试。", retryable: true },
  RUNTIME_PROTOCOL_ERROR: { message: "运行时返回的数据无效。", retryable: false },
};

/** 只读取稳定错误码并生成本地安全文案，禁止 native 诊断进入 React state。 */
export function normalizeReviewFailure(error: unknown): ReviewFailure {
  const candidate =
    error !== null && typeof error === "object" ? (error as { code?: unknown }) : undefined;
  const code = typeof candidate?.code === "string" ? candidate.code : "RUNTIME_UNAVAILABLE";
  const safe = REVIEW_FAILURES[code] ?? REVIEW_FAILURES["RUNTIME_UNAVAILABLE"]!;
  return { code, ...safe };
}

/** 协议归属校验失败时使用固定错误，避免把 native DTO 内容拼入 UI。 */
export function reviewProtocolFailure(message = "运行时返回的数据无效。"): ReviewFailure {
  return { code: "RUNTIME_PROTOCOL_ERROR", message, retryable: false };
}

/** 为缺少 App 注入的场景提供 fail-closed 端口，不在 feature 内偷偷创建 Tauri adapter。 */
function unavailable(): Promise<never> {
  return Promise.reject({ code: "RUNTIME_UNAVAILABLE" });
}

export const unavailableReviewPort: ReviewPort = {
  catalog: unavailable,
  snapshot: unavailable,
  fileDiff: unavailable,
  apply: unavailable,
  cancel: unavailable,
  subscribeInvalidated: unavailable,
};
