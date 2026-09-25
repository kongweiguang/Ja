// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ConversationHistoryPort } from "./ports";

/** 按服务端 Unicode 游标逐页读取；身份、长度和前台栅栏任一变化都拒绝交付残缺内容。 */
export async function readFullMessageContent(
  readPage: NonNullable<ConversationHistoryPort["messageContentRead"]>,
  threadId: string,
  messageId: string,
  isCurrent: () => boolean = () => true,
): Promise<string> {
  let offsetCharacters = 0;
  let totalCharacters: number | undefined;
  const chunks: string[] = [];
  for (;;) {
    if (!isCurrent()) throw new Error("当前对话已切换，请重新打开内容。");
    const page = await readPage({ threadId, messageId, offsetCharacters, limitCharacters: 32_768 });
    if (!isCurrent()) throw new Error("当前对话已切换，请重新打开内容。");
    const end = page.nextOffsetCharacters ?? page.totalCharacters;
    if (
      page.messageId !== messageId ||
      page.offsetCharacters !== offsetCharacters ||
      (totalCharacters !== undefined && page.totalCharacters !== totalCharacters) ||
      page.truncated !== (page.nextOffsetCharacters !== null) ||
      (page.truncated && end <= offsetCharacters) ||
      end < offsetCharacters ||
      end > page.totalCharacters ||
      Array.from(page.content).length !== end - offsetCharacters
    )
      throw new Error("完整内容的分页数据不一致。");
    chunks.push(page.content);
    offsetCharacters = end;
    totalCharacters = page.totalCharacters;
    if (!page.truncated) return chunks.join("");
  }
}

/** 最终答复按已提交历史分页查齐本次 Tool 之后的所有文本段，避免只读当前可见页漏掉前缀。 */
export async function readFullAnswerContent(
  history: Pick<ConversationHistoryPort, "threadRead" | "messageContentRead">,
  threadId: string,
  finalMessageId: string,
  isCurrent: () => boolean = () => true,
  minimumRevision?: number,
  expectedTurnId?: string,
): Promise<string> {
  const readPage = history.messageContentRead;
  if (readPage === undefined) throw new Error("完整回复暂时不可用。");
  const consistencyDeadline = Date.now() + 10_000;
  let ordered: string[];
  for (;;) {
    const scanned = await scanAnswerMessageIds(
      history,
      threadId,
      finalMessageId,
      expectedTurnId,
      isCurrent,
    );
    if (scanned.messageIds.length > 0) {
      ordered = scanned.messageIds;
      break;
    }
    if (
      minimumRevision === undefined ||
      scanned.revision >= minimumRevision ||
      Date.now() >= consistencyDeadline
    )
      throw new Error("最终回复不在当前历史路径中。");
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  const segments: string[] = [];
  for (const messageId of ordered) {
    segments.push(
      await readFullMessageContent(
        (input) => readPage.call(history, input),
        threadId,
        messageId,
        isCurrent,
      ),
    );
  }
  const last = segments.pop() ?? "";
  const previous = segments.join("");
  if (previous === "" || last.startsWith(previous)) return last;
  if (previous.endsWith(last)) return previous;
  return previous + last;
}

/** 历史页从新到旧扫描到最近 Tool/USER 边界；直播事件用原始 ID，历史用公开哈希 ID，
 * 因此同一 Turn 的最新 final 可作为两者的安全关联，正文仍按历史公开身份单独分页。 */
async function scanAnswerMessageIds(
  history: Pick<ConversationHistoryPort, "threadRead">,
  threadId: string,
  finalMessageId: string,
  expectedTurnId: string | undefined,
  isCurrent: () => boolean,
): Promise<{ messageIds: string[]; revision: number }> {
  let cursor: string | undefined;
  let revision: number | undefined;
  let matchedTurnId: string | undefined;
  let reachedBoundary = false;
  const visited = new Set<string>();
  const messageIdsNewestFirst: string[] = [];
  while (!reachedBoundary) {
    if (!isCurrent()) throw new Error("当前对话已切换，请重新打开回复。");
    const page = await history.threadRead({ threadId, tail: true, cursor, limit: 200 });
    if (!isCurrent()) throw new Error("当前对话已切换，请重新打开回复。");
    if (page.threadId !== threadId || (revision !== undefined && page.revision !== revision))
      throw new Error("对话历史在读取期间发生变化，请重新打开回复。");
    revision = page.revision;
    for (let index = page.items.length - 1; index >= 0; index -= 1) {
      const item = page.items[index];
      if (item === undefined) continue;
      if (matchedTurnId === undefined) {
        if (
          item.itemId !== finalMessageId &&
          !(
            expectedTurnId !== undefined &&
            item.kind === "final_answer" &&
            item.turnId === expectedTurnId
          )
        )
          continue;
        if (item.kind !== "final_answer") throw new Error("最终回复身份已变化。");
        matchedTurnId = item.turnId;
        messageIdsNewestFirst.push(item.itemId);
        continue;
      }
      if (
        item.turnId !== matchedTurnId ||
        item.kind === "tool_call" ||
        item.kind === "user_input" ||
        item.kind === "thread_message"
      ) {
        reachedBoundary = true;
        break;
      }
      if (item.kind === "assistant_progress") messageIdsNewestFirst.push(item.itemId);
    }
    if (reachedBoundary || page.nextCursor === null) break;
    if (page.nextCursor === cursor || visited.has(page.nextCursor))
      throw new Error("历史分页游标无法继续。");
    visited.add(page.nextCursor);
    cursor = page.nextCursor;
  }
  return {
    messageIds: matchedTurnId === undefined ? [] : messageIdsNewestFirst.reverse(),
    revision: revision ?? -1,
  };
}
