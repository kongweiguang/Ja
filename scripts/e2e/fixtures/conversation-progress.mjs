// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

const READ_CALL_ID = "call_progress_read";
const SHELL_CALL_ID = "call_progress_shell";
const COMMENTARY_1 = "JA_PROGRESS_COMMENTARY_1";
const COMMENTARY_2 = "JA_PROGRESS_COMMENTARY_2";
const COMMENTARY_3 = "JA_PROGRESS_COMMENTARY_3";
const READ_MARKER = "JA_PROGRESS_READ_OK";
const SHELL_MARKER = "JA_PROGRESS_SHELL_OK";
const FINAL_MARKER = "JA_PROGRESS_FINAL_OK";

/** 将一个 Responses SSE 事件固定为严格 JSONL-compatible frame，避免 fixture 依赖宽松解析。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequenceNumber,
    ...payload,
  })}\n\n`;
}

/** 构造 provider 终态 envelope，补齐 Native/JVM adapter 需要的 usage 与 output 字段。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "gpt-5.6-sol",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 20,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 12,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 32,
    };
  }
  return response;
}

/** 生成公开摘要 delta；private reasoning 不进入 fixture，验收只证明用户可见叙事。 */
function summaryEvent(text, sequenceNumber, itemId) {
  return event("response.reasoning_summary_text.delta", sequenceNumber, {
    delta: text,
    item_id: itemId,
    output_index: 0,
    sequence_number: sequenceNumber,
    summary_index: 0,
  });
}

/** 生成一个严格 function_call output item，并使用真实内建 Tool 名称。 */
function toolStream(responseId, itemId, callId, name, argumentsValue, summary, text) {
  const item = {
    id: itemId,
    type: "function_call",
    call_id: callId,
    name,
    arguments: JSON.stringify(argumentsValue),
  };
  const messageId = `${itemId}_message`;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  let sequence = 0;
  const frame = (type, payload) => event(type, sequence++, payload);
  const frames = [
    frame("response.created", {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
  ];
  if (summary !== undefined) frames.push(summaryEvent(summary, sequence++, `${itemId}_summary`));
  if (text.trim() !== "") {
    frames.push(
      frame("response.output_text.delta", {
        content_index: 0,
        delta: text,
        item_id: messageId,
        output_index: 0,
        logprobs: [],
      }),
      frame("response.output_text.done", {
        content_index: 0,
        item_id: messageId,
        output_index: 0,
        text,
      }),
    );
  }
  frames.push(
    frame("response.output_item.added", {
      output_index: 1,
      item: { ...item, arguments: "" },
    }),
    frame("response.function_call_arguments.done", {
      item_id: itemId,
      arguments: item.arguments,
      output_index: 1,
    }),
    frame("response.output_item.done", { output_index: 1, item }),
    frame("response.completed", {
      response: responseEnvelope(responseId, "completed", [message, item]),
    }),
  );
  return frames.join("");
}

/** 生成不含 Tool 的最终答复，确保第三轮仍留下一个独立公开摘要节点。 */
function finalStream() {
  const responseId = "resp_progress_final";
  const messageId = "message_progress_final";
  const text = `已完成 read 和 shell，结果标记为 ${FINAL_MARKER}。`;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    summaryEvent(
      `两个工具都已返回，现在整理最终结果。${COMMENTARY_3}`,
      1,
      "summary_progress_final",
    ),
    event("response.output_text.delta", 2, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 3, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    event("response.completed", 4, {
      response: responseEnvelope(responseId, "completed", [message]),
    }),
  ].join("");
}

/** 自动标题请求使用普通文本结束，避免标题后台请求污染本轮 Tool continuation 计数。 */
function titleStream() {
  const responseId = "resp_progress_title";
  const messageId = "message_progress_title";
  const text = "工作过程验收";
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    event("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [message]),
    }),
  ].join("");
}

/** 有界读取请求体，只用于识别 continuation 结构，不保存 prompt、凭据或完整输入。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("conversation progress request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 分帧输出并在关键边界让 driver 观察 live DOM，避免“最终 DOM 顺序”冒充实时证据。 */
async function writeDelayedStream(response, stream, stages, step, textGate) {
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": "text/event-stream; charset=utf-8",
    connection: "close",
  });
  const frames = stream.split("\n\n").filter((frame) => frame.length > 0);
  for (const frame of frames) {
    response.write(`${frame}\n\n`);
    if (frame.includes("reasoning_summary_text.delta")) stages.push(`summary_${step}`);
    if (frame.includes("output_text.delta")) stages.push(`text_${step}`);
    if (frame.includes("output_item.added")) stages.push(`tool_${step}`);
    if (textGate !== undefined && frame.includes("output_text.delta")) await textGate;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 90));
  }
  response.end();
}

/** 启动只监听 IPv4 loopback 的 Responses fixture，按真实 function_call_output 推进三轮模型协议。 */
export async function startConversationProgressFixture() {
  const attempts = [];
  const stages = [];
  let releaseFirstText;
  const firstTextGate = new Promise((resolvePromise) => {
    releaseFirstText = resolvePromise;
  });
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const serialized = JSON.stringify(payload?.input ?? []);
      const isTitle =
        serialized.includes("<user_request>") && serialized.includes("<assistant_reply>");
      if (isTitle) {
        attempts.push({ kind: "title" });
        await writeDelayedStream(response, titleStream(), stages, "title");
        return;
      }
      const hasShellOutput = serialized.includes(SHELL_CALL_ID);
      const hasReadOutput = serialized.includes(READ_CALL_ID);
      const step = hasShellOutput ? 2 : hasReadOutput ? 1 : 0;
      const instructions = String(payload.instructions ?? "").replace(/\s+/g, " ");
      attempts.push({
        kind: "turn",
        step,
        progressInstruction: instructions.includes(
          "public progress update before the first Tool call",
        ),
      });
      if (step === 0) {
        await writeDelayedStream(
          response,
          toolStream(
            "resp_progress_read",
            "item_progress_read",
            READ_CALL_ID,
            "read",
            { path: "no-head-untracked.txt", offset: 1, limit: 8 },
            undefined,
            `我先检查 fixture 内容。${COMMENTARY_1}`,
          ),
          stages,
          "read",
          firstTextGate,
        );
        return;
      }
      if (step === 1) {
        await writeDelayedStream(
          response,
          toolStream(
            "resp_progress_shell",
            "item_progress_shell",
            SHELL_CALL_ID,
            "shell",
            { command: `Write-Output ${SHELL_MARKER}`, timeout_ms: 5_000 },
            `读取成功，我再执行一次低影响 Shell 回显 ${READ_MARKER}。${COMMENTARY_2}`,
            "读取结果已收到，继续做 Shell 校验。",
          ),
          stages,
          "shell",
        );
        return;
      }
      if (step === 2) {
        await writeDelayedStream(response, finalStream(), stages, "final");
        return;
      }
      throw new Error("unexpected conversation progress continuation");
    } catch (error) {
      response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: String(error?.message ?? error).slice(0, 200) }));
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    stages,
    /** 由真实 DOM 确认首段正文后才释放 Tool，避免把机器负载造成的采样延迟误判为顺序错误。 */
    releaseFirstText() {
      releaseFirstText();
    },
    /** 仅返回低敏阶段与请求种类，报告不包含 Provider request body。 */
    snapshot() {
      return { attempts: attempts.map((attempt) => ({ ...attempt })), stages: [...stages] };
    },
    /** 关闭唯一 loopback listener，保证 fixture 生命周期不越过 runner cleanup。 */
    async close() {
      releaseFirstText();
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

export const conversationProgressFixtureMarkers = Object.freeze({
  commentary1: COMMENTARY_1,
  commentary2: COMMENTARY_2,
  commentary3: COMMENTARY_3,
  final: FINAL_MARKER,
  read: READ_MARKER,
  shell: SHELL_MARKER,
});
