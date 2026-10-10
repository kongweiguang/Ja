// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFullAnswerContent, readFullItemContent } from "./readFullMessageContent";
import type { ConversationHistoryPort } from "./ports";
import type { TimelineSnapshotItem } from "../domain/timelineContracts";

const EXPORT_PAGE_SIZE = 200;

/** 生成 Windows 文件系统可用的短名称，保留 Unicode 标题并避开设备保留名。 */
export function conversationMarkdownFilename(title: string): string {
  const basename = Array.from(title.trim(), (character) => {
    const code = character.charCodeAt(0);
    return /[<>:"/\\|?*]/u.test(character) || code < 0x20 || code === 0x7f ? "_" : character;
  })
    .join("")
    .replace(/[. ]+$/u, "")
    .slice(0, 120);
  return `Ja-${basename || "对话"}.md`;
}

/** 固定并复核 revision，并保留 adapter reader 的 this 绑定，避免并发混读或丢失 bridge。 */
export async function exportConversationMarkdown(
  history: Pick<ConversationHistoryPort, "threadRead" | "itemContentRead">,
  input: {
    readonly threadId: string;
    readonly title: string;
    readonly isCurrent?: () => boolean;
  },
): Promise<{ readonly revision: number; readonly markdown: string }> {
  const isCurrent = input.isCurrent ?? (() => true);
  let cursor: string | undefined;
  let revision: number | undefined;
  const pages: TimelineSnapshotItem[][] = [];
  const seenCursors = new Set<string>();
  const seenItems = new Set<string>();

  for (;;) {
    if (!isCurrent()) throw new Error("当前对话已切换，请重新开始导出。");
    const page = await history.threadRead({
      threadId: input.threadId,
      cursor,
      limit: EXPORT_PAGE_SIZE,
      tail: true,
    });
    if (!isCurrent()) throw new Error("当前对话已切换，请重新开始导出。");
    if (page.threadId !== input.threadId || (revision !== undefined && page.revision !== revision))
      throw new Error("对话在导出期间发生变化，请重试。");
    revision = page.revision;
    for (const item of page.items) {
      if (seenItems.has(item.itemId)) throw new Error("历史分页重复了消息，无法完整导出。");
      seenItems.add(item.itemId);
    }
    pages.push(page.items);
    const nextCursor = page.nextCursor ?? null;
    if (nextCursor === null) break;
    if (nextCursor === cursor || seenCursors.has(nextCursor))
      throw new Error("历史分页游标无法继续，请重试。");
    seenCursors.add(nextCursor);
    cursor = nextCursor;
  }

  const chronological = pages.reverse().flat();
  // The Tauri history adapter reads bridge state from `this`, so callback based paging must keep its receiver.
  const readItemContent = history.itemContentRead.bind(history);
  const finalTurns = new Set(
    chronological.flatMap((item) => (item.kind === "final_answer" ? [item.turnId] : [])),
  );
  const blocks: string[] = [
    `# ${input.title}`,
    "",
    `Thread: ${input.threadId}`,
    `Revision: ${revision ?? 0}`,
    "",
  ];
  for (const item of chronological) {
    if (!isCurrent()) throw new Error("当前对话已切换，请重新开始导出。");
    if (item.kind === "user_input") {
      const fullText = await readFullItemContent(
        readItemContent,
        input.threadId,
        item.itemId,
        isCurrent,
      );
      const content = userInputMarkdown(item, fullText);
      blocks.push(`## 用户 · ${item.createdAt}`, "", content, "");
    } else if (item.kind === "final_answer") {
      const content = await readFullAnswerContent(
        history,
        input.threadId,
        item.itemId,
        isCurrent,
        revision,
        item.turnId,
      );
      blocks.push(`## 助手 · ${item.createdAt}`, "", content, "");
    } else if (item.kind === "assistant_progress" && !finalTurns.has(item.turnId)) {
      const content = await readFullItemContent(
        readItemContent,
        input.threadId,
        item.itemId,
        isCurrent,
      );
      blocks.push(`## 助手（未完成） · ${item.createdAt}`, "", content, "");
    } else if (item.kind === "thread_message") {
      const content = await readFullItemContent(
        readItemContent,
        input.threadId,
        item.itemId,
        isCurrent,
      );
      blocks.push(`## 来自 ${item.sourceTitle} · ${item.createdAt}`, "", content, "");
    }
  }
  if (!isCurrent()) throw new Error("当前对话已切换，请重新开始导出。");
  const latest = await history.threadRead({ threadId: input.threadId, limit: 1, tail: true });
  if (!isCurrent() || latest.threadId !== input.threadId || latest.revision !== revision)
    throw new Error("对话在导出期间发生变化，请重试。");
  return { revision: revision ?? latest.revision, markdown: `${blocks.join("\n").trimEnd()}\n` };
}

/** 用户输入按原引用顺序展开，同时用完整正文替换 Snapshot 的预览。 */
function userInputMarkdown(
  item: Extract<TimelineSnapshotItem, { kind: "user_input" }>,
  text: string,
): string {
  let includedText = false;
  return item.content
    .map((block) => {
      switch (block.type) {
        case "text":
          if (includedText) return "";
          includedText = true;
          return text;
        case "attachment":
          return `[附件：${item.attachments.find((attachment) => attachment.attachmentId === block.attachmentId)?.displayName ?? "不可用"}]`;
        case "workspace_reference":
          return `工作区引用：${block.relativePath}`;
        case "skill_reference":
          return `Skill：${block.skillId}`;
      }
    })
    .join("\n\n");
}
