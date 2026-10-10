// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { latestUserPrompt } from "./sse-webview2.mjs";

/** 同角色内容合并和 tool_result 用户块不能把已取消的首轮重新选为当前场景。 */
test("selects the latest user text in merged Anthropic messages", () => {
  assert.equal(
    latestUserPrompt({
      messages: [
        {
          role: "user",
          content: [
            { type: "text", text: "JA_SSE_CANCEL" },
            { type: "text", text: "JA_SSE_TOOL" },
          ],
        },
        { role: "assistant", content: [{ type: "tool_use", id: "call_read" }] },
        { role: "user", content: [{ type: "tool_result", content: "JA_SSE_FILE_CONTENT" }] },
      ],
    }),
    "JA_SSE_TOOL",
  );
});

/** 两种 OpenAI wire 的用户正文继续走同一选择规则，不将系统提示或模型输出当问题。 */
test("selects the latest OpenAI user text", () => {
  assert.equal(
    latestUserPrompt({
      input: [
        { role: "system", content: "JA_SSE_CANCEL" },
        { role: "user", content: [{ type: "input_text", text: "JA_SSE_RECOVER" }] },
      ],
    }),
    "JA_SSE_RECOVER",
  );
  assert.equal(
    latestUserPrompt({
      messages: [
        { role: "user", content: "JA_SSE_CANCEL" },
        { role: "assistant", content: "prior response" },
        { role: "user", content: "JA_SSE_TOOL" },
      ],
    }),
    "JA_SSE_TOOL",
  );
  assert.equal(latestUserPrompt({ messages: [] }), "");
});
