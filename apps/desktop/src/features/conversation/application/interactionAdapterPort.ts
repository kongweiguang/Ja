// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  InteractionAnswer,
  InteractionEvent,
  InteractionPort,
  InteractionSnapshot,
} from "@/features/conversation";

/**
 * 组合层注入的最小结构契约；故意不引用 api/tauri 或 wire 类型，避免 application 层反向依赖
 * 原生边界。真实 Tauri adapter 只要满足这些语义方法即可被安全接入。
 */
interface InteractionAdapterLike {
  read(input: { threadId: string }): Promise<InteractionSnapshot>;
  observe(input: { threadId: string }): Promise<InteractionSnapshot & { observationId: string }>;
  unobserve(input: { observationId: string }): Promise<unknown>;
  draftSave(input: {
    threadId: string;
    requestId: string;
    expectedDraftRevision: number;
    answers: readonly InteractionAnswer[];
    page: number;
    collapsed: boolean;
    idempotencyKey: string;
  }): Promise<InteractionSnapshot>;
  respond(input: {
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

/** 只投影协议允许的答案字段，避免组件层扩展字段沿着 spread 泄漏到 JA-RPC。 */
function toWireAnswer(answer: InteractionAnswer): InteractionAnswer {
  return {
    questionId: answer.questionId,
    optionIds: [...answer.optionIds],
    freeText: answer.freeText,
    skipped: answer.skipped,
  };
}

/** 领域 port 只承接已校验 wire；观察 ACK 后再触发对账以封住注册间隙。 */
export function createInteractionPort(
  adapter: InteractionAdapterLike,
  events: { subscribe(listener: (event: InteractionEvent) => void): () => void },
): InteractionPort {
  /** 冲突必须回读同一请求，保留用户本地编辑且不给错误附加猜测快照。 */
  const mutate = async (
    input: { threadId: string; requestId: string },
    action: () => Promise<InteractionSnapshot>,
  ): Promise<InteractionSnapshot> => {
    try {
      return await action();
    } catch (error) {
      if (
        typeof error === "object" &&
        error !== null &&
        "code" in error &&
        error.code === "INTERACTION_REVISION_CONFLICT"
      ) {
        const snapshot = await adapter.read(input);
        throw { code: "INTERACTION_REVISION_CONFLICT", snapshot };
      }
      throw error;
    }
  };
  return {
    read: (input) => adapter.read(input),
    subscribe: (input, listener) => {
      let disposed = false;
      let observationId: string | undefined;
      let retryTimer: ReturnType<typeof setTimeout> | undefined;
      let attempts = 0;
      const unsubscribe = events.subscribe((event) => {
        if (!disposed && event.threadId === input.threadId) listener(event);
      });
      /** 观察失败不制造服务端序列；有界退避重新注册，隐藏后立即停止重试。 */
      const observe = (): void => {
        void adapter
          .observe({ threadId: input.threadId })
          .then((snapshot) => {
            observationId = snapshot.observationId;
            if (disposed) {
              void adapter.unobserve({ observationId }).catch(() => undefined);
              return;
            }
            listener({
              kind: "snapshot_changed",
              threadId: input.threadId,
              eventSequence: snapshot.eventSequence,
            });
          })
          .catch(() => {
            if (!disposed) {
              const delay = Math.min(2_000, 100 * 2 ** Math.min(attempts++, 5));
              retryTimer = setTimeout(observe, delay);
            }
          });
      };
      observe();
      return () => {
        disposed = true;
        if (retryTimer !== undefined) clearTimeout(retryTimer);
        unsubscribe();
        if (observationId !== undefined)
          void adapter.unobserve({ observationId }).catch(() => undefined);
      };
    },
    saveDraft: (input) =>
      mutate({ threadId: input.threadId, requestId: input.requestId }, () =>
        adapter.draftSave({
          threadId: input.threadId,
          requestId: input.requestId,
          expectedDraftRevision: input.expectedDraftRevision,
          answers: input.answers.map(toWireAnswer),
          page: input.page,
          collapsed: input.collapsed,
          idempotencyKey: input.idempotencyKey,
        }),
      ),
    submit: (input) =>
      mutate({ threadId: input.threadId, requestId: input.requestId }, () =>
        adapter.respond({
          threadId: input.threadId,
          requestId: input.requestId,
          expectedRevision: input.expectedRevision,
          answers: input.answers.map(toWireAnswer),
          idempotencyKey: input.idempotencyKey,
        }),
      ),
    cancel: (input) =>
      mutate({ threadId: input.threadId, requestId: input.requestId }, () =>
        adapter.cancel({
          threadId: input.threadId,
          requestId: input.requestId,
          expectedRevision: input.expectedRevision,
          idempotencyKey: input.idempotencyKey,
        }),
      ),
  };
}
