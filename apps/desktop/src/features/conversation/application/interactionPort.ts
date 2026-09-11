// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 用户可见选项的稳定身份；显示文案变化不能改变已保存的答案。 */
export interface InteractionOption {
  optionId: string;
  label: string;
  description?: string;
  recommended?: boolean;
}

export type InteractionQuestionKind = "single" | "multiple" | "text";

/** 一批问题最多三题，controller 会保证一页只投影其中一题。 */
export interface InteractionQuestion {
  questionId: string;
  prompt: string;
  type: InteractionQuestionKind;
  required: boolean;
  allowFreeText: boolean;
  options?: readonly InteractionOption[];
}

export interface InteractionAnswer {
  questionId: string;
  optionIds: readonly string[];
  freeText: string | null;
  skipped: boolean;
}

export interface InteractionRequest {
  requestId: string;
  threadId: string;
  turnId?: string | null;
  toolCallId?: string | null;
  planRevisionId?: string | null;
  runId?: string | null;
  goalId?: string | null;
  questions: readonly InteractionQuestion[];
  answers: readonly InteractionAnswer[];
  status: "pending" | "answered" | "cancelled" | "superseded";
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface InteractionSnapshot {
  threadId: string;
  eventSequence: number;
  request: InteractionRequest | null;
  draft: {
    revision: number;
    answers: readonly InteractionAnswer[];
    page?: number;
    collapsed?: boolean;
  } | null;
  /** 已回答问题可能因重启仍等待显式恢复，不能从 request.status 猜测完成。 */
  resumeState?:
    | "none"
    | "waiting_for_answer"
    | "waiting_to_resume"
    | "resuming"
    | "settled"
    | "closed";
}

export type InteractionEvent =
  | { kind: "snapshot_changed"; threadId: string; eventSequence: number }
  | { kind: "answered"; threadId: string; eventSequence: number }
  | { kind: "cancelled"; threadId: string; eventSequence: number };

export interface InteractionRevisionConflict {
  code: "INTERACTION_REVISION_CONFLICT";
  snapshot: InteractionSnapshot;
}

/**
 * Interaction adapter 是 Java/JA-RPC 的唯一前端边界。controller 只依赖语义方法，
 * 因而不会在 UI 中拼装 wire DTO，也不会用浏览器本地数据伪造服务端事实。
 */
export interface InteractionPort {
  read(input: { threadId: string }): Promise<InteractionSnapshot>;
  subscribe(
    input: { threadId: string; afterSequence: number },
    listener: (event: InteractionEvent) => void,
  ): () => void;
  saveDraft(input: {
    threadId: string;
    requestId: string;
    expectedDraftRevision: number;
    answers: readonly InteractionAnswer[];
    page: number;
    collapsed: boolean;
    idempotencyKey: string;
  }): Promise<InteractionSnapshot>;
  submit(input: {
    threadId: string;
    requestId: string;
    expectedRevision: number;
    answers: readonly InteractionAnswer[];
    idempotencyKey: string;
  }): Promise<InteractionSnapshot>;
  cancel(input: {
    threadId: string;
    requestId: string;
    expectedRevision: number;
    idempotencyKey: string;
  }): Promise<InteractionSnapshot>;
}

/** 只将服务端明确的 CAS 冲突识别为可保留本地答案的错误，其它错误继续走重试提示。 */
export function isInteractionRevisionConflict(
  error: unknown,
): error is InteractionRevisionConflict {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { code?: unknown }).code === "INTERACTION_REVISION_CONFLICT" &&
    typeof (error as { snapshot?: unknown }).snapshot === "object" &&
    (error as { snapshot?: unknown }).snapshot !== null
  );
}
