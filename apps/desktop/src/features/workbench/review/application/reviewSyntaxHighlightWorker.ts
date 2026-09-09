// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createSyntaxBudget, highlightCodeLines, type SyntaxToken } from "@/shared/syntax";
import type { ReviewSyntaxFragment, ReviewSyntaxResult } from "./reviewSyntaxHighlightClient";

interface HighlightRequest {
  readonly requestId: number;
  readonly filePath: string;
  readonly lineCount: number;
  readonly fragments: readonly ReviewSyntaxFragment[];
}

/**
 * 每个 hunk 的旧、新两侧作为独立片段解析，保证多行注释等 parser 状态不会跨不连续 hunk，
 * 也不会从删除侧污染新增侧；共享预算耗尽时未着色行仍由 UI 以完整纯文本显示。
 */
function highlightRequest(request: HighlightRequest): ReviewSyntaxResult {
  const result: Array<readonly SyntaxToken[] | undefined> = Array.from({
    length: request.lineCount,
  });
  const budget = createSyntaxBudget();
  for (const fragment of request.fragments) {
    const highlighted = highlightCodeLines(request.filePath, fragment.lines, budget);
    if (highlighted === undefined) continue;
    for (const [index, tokens] of highlighted.entries()) {
      const lineIndex = fragment.lineIndexes[index];
      if (lineIndex !== undefined && lineIndex >= 0 && lineIndex < result.length)
        result[lineIndex] = tokens;
    }
  }
  return result;
}

/** Worker 错误只回传固定码，不把源码或 parser 诊断泄露到 Renderer。 */
function handleHighlightRequest(event: MessageEvent<HighlightRequest>): void {
  try {
    self.postMessage({
      requestId: event.data.requestId,
      lines: highlightRequest(event.data),
    });
  } catch {
    self.postMessage({ requestId: event.data.requestId, error: "highlight_failed" });
  }
}

self.addEventListener("message", handleHighlightRequest);
