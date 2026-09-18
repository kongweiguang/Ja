// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { InteractionAnswer, InteractionRequest } from "./interactionPort";
import type { TimelineItemAdapter, ToolPresentation } from "../domain/timelineTypes";

/** 只把服务端问题快照中的稳定文案与已确认答案组合成有限长度的临时摘要。 */
function interactionAnswerSummary(
  request: InteractionRequest,
  answers: Readonly<Record<string, InteractionAnswer>>,
): string {
  const prefix = "已选择：";
  let summary = prefix;
  for (const question of request.questions) {
    const answer =
      answers[question.questionId] ??
      request.answers.find((candidate) => candidate.questionId === question.questionId);
    const labels =
      answer === undefined || answer.skipped
        ? answer?.skipped === true
          ? ["已跳过"]
          : ["未回答"]
        : [
            ...(question.options ?? [])
              .filter((option) => answer.optionIds.includes(option.optionId))
              .map((option) => option.label),
            ...(answer.freeText?.trim() ? [answer.freeText.trim()] : []),
          ];
    const value = labels.length === 0 ? "未回答" : labels.join("、");
    const entry = question.prompt + "：" + value;
    if (summary.length > prefix.length) summary += "；";
    if (summary.length + entry.length > 980) {
      summary += "…";
      break;
    }
    summary += entry;
  }
  return summary;
}

/** 终态 ToolResult 已是权威事实；临时投影只能补 pending/running 的 ACK 空窗。 */
function isTerminalToolPresentation(presentation: ToolPresentation | undefined): boolean {
  return (
    presentation?.status === "success" ||
    presentation?.status === "error" ||
    presentation?.status === "cancelled"
  );
}

/** 用回答事实原位补齐 Tool 行，优先复用已有 itemId，避免 ACK 与规范事件之间产生第二行。 */
function answeredInteractionItem(
  request: InteractionRequest,
  answers: Readonly<Record<string, InteractionAnswer>>,
  existing: TimelineItemAdapter | undefined,
): TimelineItemAdapter {
  const presentation = existing?.metadata?.presentation;
  const fallbackPresentation: ToolPresentation = {
    kind: presentation?.kind ?? "read",
    title: presentation?.title ?? "询问用户",
    status: "success",
    inputPreview: presentation?.inputPreview,
    outputPreview: presentation?.outputPreview,
    summary: interactionAnswerSummary(request, answers),
    relativePaths: presentation?.relativePaths ?? [],
    command: presentation?.command,
    relativeCwd: presentation?.relativeCwd,
    stdout: presentation?.stdout,
    stderr: presentation?.stderr,
    exitCode: presentation?.exitCode,
    durationMs: presentation?.durationMs,
    truncated: presentation?.truncated ?? false,
    artifactId: presentation?.artifactId,
  };
  if (existing === undefined) {
    return {
      itemId: "interaction_result_" + request.requestId,
      threadId: request.threadId,
      turnId: request.turnId!,
      kind: "tool_call",
      status: "completed",
      title: "询问用户",
      metadata: {
        callId: request.toolCallId!,
        toolName: "request_user_input",
        toolKind: fallbackPresentation.kind,
        presentation: fallbackPresentation,
        requiresUserAction: false,
      },
      createdAt: request.updatedAt,
    };
  }
  return {
    ...existing,
    status: "completed",
    title: existing.title ?? "询问用户",
    metadata: {
      ...existing.metadata,
      callId: request.toolCallId!,
      toolName: "request_user_input",
      toolKind: fallbackPresentation.kind,
      presentation: fallbackPresentation,
      requiresUserAction: false,
    },
  };
}

/**
 * 将已回答 ACK 投影成与规范 ToolResult 相同的 Work 行，直到事件或 Snapshot 提供终态事实。
 * 该函数只返回渲染副本，不修改 Timeline Store；规范终态一到，原始 item 会自动接管。
 */
export function projectAnsweredInteractionResult(
  items: readonly TimelineItemAdapter[],
  request: InteractionRequest | null,
  answers: Readonly<Record<string, InteractionAnswer>>,
): readonly TimelineItemAdapter[] {
  if (
    request?.status !== "answered" ||
    typeof request.turnId !== "string" ||
    typeof request.toolCallId !== "string"
  )
    return items;
  const existingIndex = items.findIndex(
    (item) =>
      item.threadId === request.threadId &&
      item.turnId === request.turnId &&
      item.kind === "tool_call" &&
      item.metadata?.callId === request.toolCallId,
  );
  const existing = existingIndex < 0 ? undefined : items[existingIndex];
  if (isTerminalToolPresentation(existing?.metadata?.presentation)) return items;
  const result = answeredInteractionItem(request, answers, existing);
  if (existingIndex < 0) return [...items, result];
  return items.map((item, index) => (index === existingIndex ? result : item));
}
