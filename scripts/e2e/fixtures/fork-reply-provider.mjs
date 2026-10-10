// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

export const forkReplyMarkers = Object.freeze({
  sourcePrompts: ["JA_FORK_SOURCE_PROMPT_1", "JA_FORK_SOURCE_PROMPT_2", "JA_FORK_SOURCE_PROMPT_3"],
  sourceAnswers: ["JA_FORK_SOURCE_ANSWER_1", "JA_FORK_SOURCE_ANSWER_2", "JA_FORK_SOURCE_ANSWER_3"],
  publicWork: ["JA_FORK_PUBLIC_WORK_1", "JA_FORK_PUBLIC_WORK_2", "JA_FORK_PUBLIC_WORK_3"],
  branchBPrompt: "JA_FORK_BRANCH_B_PROMPT",
  branchBAnswer: "JA_FORK_BRANCH_B_ANSWER",
  branchCPrompt: "JA_FORK_BRANCH_C_PROMPT",
  branchCAnswer: "JA_FORK_BRANCH_C_ANSWER",
  longAnswerPrompt: "JA_FORK_LONG_ANSWER_PROMPT",
  longAnswer: "JA_FORK_LONG_ANSWER_MARKER",
  longBranchPrompt: "JA_FORK_LONG_BRANCH_PROMPT",
  longBranchAnswer: "JA_FORK_LONG_BRANCH_ANSWER",
  activePrompt: "JA_FORK_ACTIVE_PROMPT",
  activePartial: "JA_FORK_ACTIVE_PARTIAL",
  activeAnswer: "JA_FORK_ACTIVE_ANSWER",
});

const longAnswerPrefix = "长正文分叉验收开始。\n\n";
const longAnswerCharacters = Array.from(
  "Frozen history remains anchored to the selected reply and can be read in stable bounded pages. ".repeat(
    900,
  ),
);
longAnswerCharacters.splice(
  65_530 - Array.from(longAnswerPrefix).length,
  0,
  ...Array.from("中文🙂"),
);
export const forkReplyLongAnswerText = `${longAnswerPrefix}${longAnswerCharacters.join("")}\n\n${forkReplyMarkers.longAnswer}`;

const answers = new Map([
  [forkReplyMarkers.sourcePrompts[0], "第一轮中文答复。\n\n第二段继续说明冻结边界。"],
  [forkReplyMarkers.sourcePrompts[1], "第二轮中文答复。\n\n第二段用于验证按回复截断。"],
  [forkReplyMarkers.sourcePrompts[2], "第三轮中文答复。\n\n这段不得出现在较早的分叉中。"],
  [forkReplyMarkers.branchBPrompt, "分支 B 的首轮答复。\n\n它从第二轮来源继续。"],
  [forkReplyMarkers.branchCPrompt, "分支 C 的首轮答复。\n\n它只继承第一轮来源。"],
  [
    forkReplyMarkers.longBranchPrompt,
    `长正文继承分支的后续答复。\n\n${forkReplyMarkers.longBranchAnswer}`,
  ],
  [forkReplyMarkers.activePrompt, "活动中的后续答复。\n\n它不属于之前的分叉点。"],
]);
export const forkReplyActiveAnswerText = `${forkReplyMarkers.activePartial} ${answers.get(forkReplyMarkers.activePrompt)}\n\n${forkReplyMarkers.activeAnswer}`;

/** Provider 载荷在解析前设上限，fixture 只在内存计算 marker 数量，不保存请求正文。 @author kongweiguang */
async function readBoundedJson(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > 8 * 1024 * 1024) throw new Error("fork reply Provider request exceeded 8 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** JSON 中只保留验收 marker 的出现次数，避免把 prompt、配置或凭据写入报告。 @author kongweiguang */
function countMarkerOccurrences(serialized, marker) {
  let count = 0;
  let offset = 0;
  while ((offset = serialized.indexOf(marker, offset)) !== -1) {
    count += 1;
    offset += marker.length;
  }
  return count;
}

/** Responses fixture 的帧序号单调递增，确保活动流中断点仍是可解析的真实 Provider 流。 @author kongweiguang */
function responseFrame(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** 生成与本轮 prompt 对应的多段答复，并在历史里留下真实公开 Commentary 节点。 @author kongweiguang */
function completedResponse(responseId, text, commentary) {
  const messageId = `message_${responseId}`;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  const envelope = (status, output) => ({
    id: responseId,
    created_at: 0,
    model: "fork-reply-loopback",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
    usage: {
      input_tokens: 24,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 18,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 42,
    },
  });
  return [
    responseFrame("response.created", 0, { response: envelope("in_progress", []) }),
    responseFrame("response.reasoning_summary_text.delta", 1, {
      delta: commentary,
      item_id: `summary_${responseId}`,
      output_index: 0,
      summary_index: 0,
    }),
    responseFrame("response.output_text.delta", 2, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    responseFrame("response.output_text.done", 3, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    responseFrame("response.completed", 4, {
      response: envelope("completed", [message]),
    }),
  ].join("");
}

/** 活动流先声明原生 reasoning/message item 与正文 part，再停在和终态完全一致的部分答复。 @author kongweiguang */
function activeResponsePrefix(responseId, finalText, commentary) {
  const messageId = `message_${responseId}`;
  const summaryId = `summary_${responseId}`;
  const partialEnd =
    finalText.indexOf(forkReplyMarkers.activePartial) + forkReplyMarkers.activePartial.length + 1;
  assert.ok(partialEnd > forkReplyMarkers.activePartial.length, "active partial marker must exist");
  const reasoning = { id: summaryId, type: "reasoning", summary: [] };
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "in_progress",
    content: [],
  };
  const envelope = {
    id: responseId,
    created_at: 0,
    model: "fork-reply-loopback",
    object: "response",
    output: [],
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status: "in_progress",
  };
  return (
    responseFrame("response.created", 0, { response: envelope }) +
    responseFrame("response.output_item.added", 1, { item: reasoning, output_index: 0 }) +
    responseFrame("response.reasoning_summary_text.delta", 2, {
      delta: `正在继续处理 ${forkReplyMarkers.publicWork[1]}。`,
      item_id: summaryId,
      output_index: 0,
      summary_index: 0,
    }) +
    responseFrame("response.reasoning_summary_text.done", 3, {
      item_id: summaryId,
      output_index: 0,
      summary_index: 0,
      text: commentary,
    }) +
    responseFrame("response.output_item.done", 4, {
      item: { ...reasoning, summary: [{ type: "summary_text", text: commentary }] },
      output_index: 0,
    }) +
    responseFrame("response.output_item.added", 5, { item: message, output_index: 1 }) +
    responseFrame("response.content_part.added", 6, {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      part: { type: "output_text", text: "", annotations: [], logprobs: [] },
    }) +
    responseFrame("response.output_text.delta", 7, {
      content_index: 0,
      delta: finalText.slice(0, partialEnd),
      item_id: messageId,
      output_index: 1,
      logprobs: [],
    })
  );
}

/** 显式 gate 放行后只发送尚未到达的正文，并以一致的 part/item/terminal 收束同一原生流。 @author kongweiguang */
function activeResponseCompletion(responseId, finalText, commentary) {
  const messageId = `message_${responseId}`;
  const summaryId = `summary_${responseId}`;
  const partialEnd =
    finalText.indexOf(forkReplyMarkers.activePartial) + forkReplyMarkers.activePartial.length + 1;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text: finalText, annotations: [], logprobs: [] }],
  };
  const reasoning = {
    id: summaryId,
    type: "reasoning",
    summary: [{ type: "summary_text", text: commentary }],
  };
  const response = {
    id: responseId,
    created_at: 0,
    model: "fork-reply-loopback",
    object: "response",
    output: [reasoning, message],
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status: "completed",
    usage: {
      input_tokens: 24,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 18,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 42,
    },
  };
  return (
    responseFrame("response.output_text.delta", 8, {
      content_index: 0,
      delta: finalText.slice(partialEnd),
      item_id: messageId,
      output_index: 1,
      logprobs: [],
    }) +
    responseFrame("response.output_text.done", 9, {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      text: finalText,
    }) +
    responseFrame("response.content_part.done", 10, {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      part: { type: "output_text", text: finalText, annotations: [], logprobs: [] },
    }) +
    responseFrame("response.output_item.done", 11, { item: message, output_index: 1 }) +
    responseFrame("response.completed", 12, { response })
  );
}

/** 只有唯一正文 prompt 才算 Agent 调用；不含 prompt 的后台标题请求由独立分支回答。 @author kongweiguang */
function findPrompt(serialized) {
  for (const marker of [
    forkReplyMarkers.activePrompt,
    forkReplyMarkers.longBranchPrompt,
    forkReplyMarkers.branchCPrompt,
    forkReplyMarkers.branchBPrompt,
    forkReplyMarkers.longAnswerPrompt,
    ...forkReplyMarkers.sourcePrompts.toReversed(),
  ]) {
    if (serialized.includes(marker)) return marker;
  }
  return undefined;
}

/** 后台标题请求会引用完整历史；按既有标题指令结构识别，避免把其中的旧 marker 当作新回合。 @author kongweiguang */
export function isAutomaticTitleRequest(payload) {
  const input = Array.isArray(payload?.input) ? payload.input : [];
  const toolsAbsentOrEmpty =
    payload?.tools === undefined || (Array.isArray(payload.tools) && payload.tools.length === 0);
  return (
    toolsAbsentOrEmpty &&
    typeof payload.instructions === "string" &&
    payload.instructions.includes("只输出一个自然语言标题") &&
    JSON.stringify(input.at(-1) ?? {}).includes("<user_request>")
  );
}

/** 暴露真实 loopback Provider；请求 body 有界、结果证据去正文化，活动 gate 在 close 时必定释放。 @author kongweiguang */
export async function startForkReplyProviderFixture() {
  const requests = [];
  let sequence = 0;
  let releaseActiveGate;
  let activeArrivedResolve;
  const activeArrived = new Promise((resolve) => {
    activeArrivedResolve = resolve;
  });
  const activeRelease = new Promise((resolve) => {
    releaseActiveGate = resolve;
  });
  let activeGateUsed = false;
  const server = createServer(async (request, response) => {
    response.setHeader("cache-control", "no-store");
    response.setHeader("connection", "close");
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const serialized = JSON.stringify(payload);
      const titleRequest = isAutomaticTitleRequest(payload);
      const marker = titleRequest ? undefined : findPrompt(serialized);
      const promptOccurrences = forkReplyMarkers.sourcePrompts.map((value) =>
        countMarkerOccurrences(serialized, value),
      );
      const longAnswerPromptOccurrences = countMarkerOccurrences(
        serialized,
        forkReplyMarkers.longAnswerPrompt,
      );
      const longAnswerOccurrences = countMarkerOccurrences(serialized, forkReplyMarkers.longAnswer);
      const answerMarkers = forkReplyMarkers.sourceAnswers;
      const answerOccurrences = answerMarkers.map((value) =>
        countMarkerOccurrences(serialized, value),
      );
      const observation = {
        ordinal: ++sequence,
        kind: titleRequest ? "automatic_title" : "agent_turn",
        prompt: marker ?? null,
        sourcePromptOccurrences: promptOccurrences,
        sourceAnswerOccurrences: answerOccurrences,
        longAnswerPromptOccurrences,
        longAnswerOccurrences,
        bytes: Buffer.byteLength(serialized, "utf8"),
      };
      requests.push(observation);
      const responseId = `resp_fork_reply_${sequence}`;
      if (titleRequest) {
        response.writeHead(200, {
          "content-type": "text/event-stream; charset=utf-8",
          "cache-control": "no-store",
        });
        response.end(completedResponse(responseId, "指定回复分叉验收", "标题生成完成。"));
        return;
      }
      const answer =
        marker === forkReplyMarkers.longAnswerPrompt
          ? forkReplyLongAnswerText
          : answers.get(marker);
      assert.ok(answer, "the loopback fixture received an unknown marker");
      const commentary = marker.startsWith("JA_FORK_SOURCE_PROMPT_")
        ? `已整理本轮公开过程 ${forkReplyMarkers.publicWork[forkReplyMarkers.sourcePrompts.indexOf(marker)]}。`
        : `已保留本分支的公开工作过程 ${marker}。`;
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-store",
      });
      if (marker !== forkReplyMarkers.activePrompt || activeGateUsed) {
        response.end(
          completedResponse(
            responseId,
            marker === forkReplyMarkers.longAnswerPrompt
              ? answer
              : `${answer}\n\n${marker.includes("SOURCE_PROMPT") ? forkReplyMarkers.sourceAnswers[forkReplyMarkers.sourcePrompts.indexOf(marker)] : marker.replace("PROMPT", "ANSWER")}`,
            commentary,
          ),
        );
        return;
      }
      activeGateUsed = true;
      const finalText = forkReplyActiveAnswerText;
      const activeCommentary = `正在继续处理 ${forkReplyMarkers.publicWork[1]}。`;
      response.write(activeResponsePrefix(responseId, finalText, activeCommentary));
      activeArrivedResolve();
      await activeRelease;
      response.end(activeResponseCompletion(responseId, finalText, activeCommentary));
    } catch (error) {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(
        JSON.stringify({ error: { code: "FIXTURE_ERROR", message: String(error).slice(0, 180) } }),
      );
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    /** 返回可审计的无正文摘要；marker occurrence 用于验证 fork seed 没有遗漏或重复注入。 @author kongweiguang */
    snapshot() {
      return requests.map((entry) => ({
        ...entry,
        sourcePromptOccurrences: [...entry.sourcePromptOccurrences],
        sourceAnswerOccurrences: [...entry.sourceAnswerOccurrences],
      }));
    },
    /** 等待活动 Provider 真正收到 prompt 并投递未结算 delta。 @author kongweiguang */
    waitForActivePartial(timeoutMs = 30_000) {
      let timer;
      return Promise.race([
        activeArrived,
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error("active Provider gate timed out")), timeoutMs);
        }),
      ]).finally(() => clearTimeout(timer));
    },
    /** finally 与正常流程共用幂等释放，避免失败时挂住本机 HTTP handler。 @author kongweiguang */
    releaseActive() {
      releaseActiveGate();
    },
    /** 先放开活动响应，再关闭 loopback socket。 @author kongweiguang */
    async close() {
      releaseActiveGate();
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
