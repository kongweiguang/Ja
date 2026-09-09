// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { parseTurnReviewDiff } from "../domain/turnReviewDiff";

interface ParseRequest {
  readonly requestId: number;
  readonly unified: string;
}

/** Worker 只返回结构化行，不回传异常正文，避免 parser 诊断进入 React 可见状态。 */
function handleParseRequest(event: MessageEvent<ParseRequest>): void {
  try {
    self.postMessage({
      requestId: event.data.requestId,
      files: parseTurnReviewDiff(event.data.unified),
    });
  } catch {
    self.postMessage({ requestId: event.data.requestId, error: "invalid_diff" });
  }
}

self.addEventListener("message", handleParseRequest);
