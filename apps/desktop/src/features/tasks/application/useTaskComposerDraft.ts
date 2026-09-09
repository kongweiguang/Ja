// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  ConversationAttachment,
  ConversationAttachmentDraftItem,
  ConversationAttachmentImportEvent,
  ConversationAttachmentPort,
  ConversationContextReference,
} from "@/features/conversation";

const MAX_ATTACHMENTS = 10;
const MAX_ATTACHMENT_BYTES = 250 * 1024 * 1024;

export interface TaskComposerDraftController {
  readonly text: string;
  readonly contextReferences: readonly ConversationContextReference[];
  readonly attachmentDraftItems: readonly ConversationAttachmentDraftItem[];
  readonly importingAttachments: boolean;
  readonly error?: string;
  readonly updateText: (text: string) => void;
  readonly updateContextReferences: (references: readonly ConversationContextReference[]) => void;
  readonly importAttachments: () => Promise<void>;
  readonly importClipboard: () => Promise<void>;
  readonly retryAttachment: (itemId: string) => Promise<void>;
  readonly removeAttachment: (itemId: string) => Promise<void>;
  readonly clearSubmitted: (attachmentIds: readonly string[]) => void;
}

/** 每次导入使用独立 identity，迟到 Channel event 不能覆盖另一次附件尝试。 */
function attachmentOperationId(): string {
  const entropy = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
  return `task-attachment-${entropy}`;
}

/**
 * Task Composer 只拥有草稿附件和结构化引用；原生导入、清理与限制继续复用 Conversation
 * AttachmentPort，避免复制 Tauri command 或在 WebView 中接触本地路径。
 */
export function useTaskComposerDraft(
  attachmentPort: ConversationAttachmentPort | undefined,
  onAttachmentRemoved?: (attachmentId: string) => void,
): TaskComposerDraftController {
  const [text, setText] = useState("");
  const [contextReferences, setContextReferences] = useState<ConversationContextReference[]>([]);
  const [attachmentDraftItems, setAttachmentDraftItems] = useState<
    ConversationAttachmentDraftItem[]
  >([]);
  const [error, setError] = useState<string>();
  const mountedRef = useRef(true);

  /** 卸载后的 Channel 事件只允许由 Native owner 完成，不再写入已销毁的 Task Tab。 */
  useEffect(
    () => () => {
      mountedRef.current = false;
    },
    [],
  );

  /** 单个事件只更新匹配 operation/item，完成项同时执行数量与总字节预算。 */
  const applyImportEvent = useCallback(
    (event: ConversationAttachmentImportEvent): void => {
      if (!mountedRef.current) return;
      setAttachmentDraftItems((current) => {
        const withoutItem = current.filter((item) => item.itemId !== event.itemId);
        switch (event.kind) {
          case "started":
            return [
              ...withoutItem,
              {
                state: "importing",
                operationId: event.operationId,
                attemptId: event.attemptId,
                itemId: event.itemId,
                fileName: event.fileName,
                sizeBytes: event.sizeBytes,
                mediaKind: event.mediaKind,
                mediaType: event.mediaType,
                phase: "copying",
                bytesCopied: 0,
                totalBytes: event.sizeBytes,
              },
            ];
          case "progress": {
            const existing = current.find(
              (item) =>
                item.state === "importing" &&
                item.itemId === event.itemId &&
                item.operationId === event.operationId &&
                item.attemptId === event.attemptId,
            );
            if (existing?.state !== "importing") return current;
            return current.map((item) =>
              item === existing
                ? {
                    ...item,
                    phase: event.phase,
                    bytesCopied: event.bytesCopied,
                    totalBytes: event.totalBytes,
                  }
                : item,
            );
          }
          case "completed": {
            const ready = current.filter(
              (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
                item.state === "ready" && item.attachmentId !== event.attachment.attachmentId,
            );
            const totalBytes = ready.reduce((total, item) => total + item.sizeBytes, 0);
            if (
              ready.length >= MAX_ATTACHMENTS ||
              totalBytes + event.attachment.sizeBytes > MAX_ATTACHMENT_BYTES
            ) {
              void attachmentPort
                ?.discardAttachment({ attachmentId: event.attachment.attachmentId })
                .catch(() => undefined);
              setError("每次最多添加 10 个附件，总大小不能超过 250 MiB。");
              return withoutItem;
            }
            return [...withoutItem, { state: "ready", itemId: event.itemId, ...event.attachment }];
          }
          case "failed":
            return [
              ...withoutItem,
              {
                state: "failed",
                operationId: event.operationId,
                attemptId: event.attemptId,
                itemId: event.itemId,
                fileName: event.fileName ?? "附件",
                sizeBytes: event.sizeBytes,
                mediaKind: event.mediaKind,
                mediaType: event.mediaType,
                code: event.code,
                message: event.message,
                retryable: event.retryable,
              },
            ];
          case "cancelled":
            return withoutItem;
        }
      });
    },
    [attachmentPort],
  );

  /** 所有附件来源共享相同错误恢复与 Channel reducer，命令失败不会清空已有草稿。 */
  const runImport = useCallback(
    async (
      invoke: (
        operationId: string,
        onEvent: (event: ConversationAttachmentImportEvent) => void,
      ) => Promise<unknown>,
    ): Promise<unknown> => {
      const operationId = attachmentOperationId();
      setError(undefined);
      try {
        return await invoke(operationId, applyImportEvent);
      } catch {
        if (mountedRef.current) setError("附件导入失败，请检查文件后重试。");
        return undefined;
      }
    },
    [applyImportEvent],
  );

  /** 文件选择器仍由原生 adapter 拥有，Task UI 只接收有界进度。 */
  const importAttachments = useCallback(async (): Promise<void> => {
    if (attachmentPort === undefined) return;
    await runImport((operationId, onEvent) =>
      attachmentPort.pickerImport({ operationId, onEvent }),
    );
  }, [attachmentPort, runImport]);

  /** 空剪贴板和系统 busy 都给出可恢复反馈，不生成虚假附件卡片。 */
  const importClipboard = useCallback(async (): Promise<void> => {
    if (attachmentPort === undefined) return;
    const result = (await runImport((operationId, onEvent) =>
      attachmentPort.clipboardImport({ operationId, onEvent }),
    )) as { outcome?: string } | undefined;
    if (!mountedRef.current || result?.outcome === "accepted") return;
    if (result?.outcome === "busy") setError("剪贴板正被其他应用占用，请稍后重试。");
    else if (result?.outcome === "nothing_importable") setError("剪贴板中没有可添加的附件。");
  }, [attachmentPort, runImport]);

  /** 重试沿用服务端 attempt identity，不能把失败项当成新的本地文件重新猜测。 */
  const retryAttachment = useCallback(
    async (itemId: string): Promise<void> => {
      if (attachmentPort === undefined) return;
      const item = attachmentDraftItems.find((candidate) => candidate.itemId === itemId);
      if (item?.state !== "failed" || !item.retryable) return;
      await runImport((operationId, onEvent) =>
        attachmentPort.retryImport({ operationId, attemptId: item.attemptId, onEvent }),
      );
    },
    [attachmentDraftItems, attachmentPort, runImport],
  );

  /**
   * 移除按真实状态选择 cancel/discard API；只有 ACK 后才删除 ready/failed 项，防止清理失败
   * 时 UI 丢失唯一恢复入口。
   */
  const removeAttachment = useCallback(
    async (itemId: string): Promise<void> => {
      if (attachmentPort === undefined) return;
      const item = attachmentDraftItems.find((candidate) => candidate.itemId === itemId);
      if (item === undefined || item.state === "removing") return;
      setError(undefined);
      try {
        if (item.state === "importing")
          await attachmentPort.cancelImport({ operationId: item.operationId, itemId });
        else if (item.state === "failed")
          await attachmentPort.discardAttempt({ attemptId: item.attemptId });
        else {
          setAttachmentDraftItems((current) =>
            current.map((candidate) =>
              candidate.itemId === itemId ? { ...item, state: "removing" } : candidate,
            ),
          );
          await attachmentPort.discardAttachment({ attachmentId: item.attachmentId });
          onAttachmentRemoved?.(item.attachmentId);
        }
        if (mountedRef.current)
          setAttachmentDraftItems((current) =>
            current.filter((candidate) => candidate.itemId !== itemId),
          );
      } catch {
        if (mountedRef.current) {
          setAttachmentDraftItems((current) =>
            current.map((candidate) => (candidate.itemId === itemId ? item : candidate)),
          );
          setError("附件暂时无法移除，请重试。");
        }
      }
    },
    [attachmentDraftItems, attachmentPort, onAttachmentRemoved],
  );

  /** 服务端已绑定的附件仅从本地草稿移除，不能再调用 discard 破坏持久消息引用。 */
  const clearSubmitted = useCallback((attachmentIds: readonly string[]): void => {
    const submitted = new Set(attachmentIds);
    setAttachmentDraftItems((current) =>
      current.filter((item) => item.state !== "ready" || !submitted.has(item.attachmentId)),
    );
    setText("");
    setContextReferences([]);
    setError(undefined);
  }, []);

  /** 对外接受只读引用集合，进入本地 state 时复制所有权，避免调用方后续修改穿透草稿。 */
  const updateContextReferences = useCallback(
    (references: readonly ConversationContextReference[]): void => {
      setContextReferences([...references]);
    },
    [],
  );

  return useMemo(
    () => ({
      text,
      contextReferences,
      attachmentDraftItems,
      importingAttachments: attachmentDraftItems.some((item) => item.state === "importing"),
      error,
      updateText: setText,
      updateContextReferences,
      importAttachments,
      importClipboard,
      retryAttachment,
      removeAttachment,
      clearSubmitted,
    }),
    [
      attachmentDraftItems,
      clearSubmitted,
      contextReferences,
      error,
      importAttachments,
      importClipboard,
      removeAttachment,
      retryAttachment,
      text,
      updateContextReferences,
    ],
  );
}

/** 只向 Composer 暴露 ready 附件摘要；进行中和失败项不能进入 TaskContentBlock。 */
export function readyTaskAttachments(
  items: readonly ConversationAttachmentDraftItem[],
): ConversationAttachment[] {
  return items.filter(
    (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
      item.state === "ready",
  );
}
