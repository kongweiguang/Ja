// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

const MARKERS = Object.freeze({
  deadline: "JA_CODING_AGENT_DEADLINE_USER",
  correction: "JA_CODING_AGENT_CORRECTION_USER",
  truncation: "JA_CODING_AGENT_TRUNCATION_USER",
});

const MAIN_CALLS = Object.freeze({
  write: Object.freeze({
    callId: "call_deadline_write",
    name: "write",
    arguments: { path: "src/deadline-proof.txt", content: "ORIGINAL_MARKER\nshell-target\n" },
  }),
  read: Object.freeze({
    callId: "call_deadline_read",
    name: "read",
    arguments: { path: "src/deadline-proof.txt" },
  }),
  grep: Object.freeze({
    callId: "call_deadline_grep",
    name: "grep",
    arguments: { pattern: "ORIGINAL_MARKER", glob: "*.txt", path: "src" },
  }),
  find: Object.freeze({
    callId: "call_deadline_find",
    name: "find",
    arguments: { pattern: "deadline-proof.txt", path: ".", limit: 10 },
  }),
  ls: Object.freeze({
    callId: "call_deadline_ls",
    name: "ls",
    arguments: { path: "src", maxEntries: 20 },
  }),
  edit: Object.freeze({
    callId: "call_deadline_edit",
    name: "edit",
    arguments: {
      path: "src/deadline-proof.txt",
      edits: [{ oldText: "ORIGINAL_MARKER", newText: "EDITED_MARKER" }],
    },
  }),
  shell: Object.freeze({
    callId: "call_deadline_shell",
    name: "exec_command",
    arguments: { cmd: "Get-Content src/deadline-proof.txt", timeout_ms: 5_000 },
  }),
});

const READ_CALLS = Object.freeze([
  MAIN_CALLS.read,
  MAIN_CALLS.grep,
  MAIN_CALLS.find,
  MAIN_CALLS.ls,
]);
const REQUEST_TIMEOUT_MS = 2_000;
const RESPONSE_DELAY_MS = 1_200;

/** 对单个 Responses 事件编码并显式指定序号，确保真实适配器按生产路径解析。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** 构造生产 OpenAI Responses 解码器要求的有限响应结构。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "coding-agent-deadline-fixture",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 32,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 16,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 48,
    };
  }
  return response;
}

/** 输出完整的 function-call 生命周期，让测试覆盖与真实模型相同的适配器路径。 */
function toolStream(responseId, calls) {
  const frames = [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
  ];
  const push = (type, payload) => frames.push(event(type, frames.length, payload));
  const items = calls.map((call, index) => {
    const item = {
      id: `item_${call.callId}`,
      type: "function_call",
      call_id: call.callId,
      name: call.name,
      arguments: JSON.stringify(call.arguments),
    };
    const argumentsText = item.arguments;
    push("response.output_item.added", {
      output_index: index,
      item: { ...item, arguments: "" },
    });
    push("response.function_call_arguments.delta", {
      item_id: item.id,
      delta: argumentsText,
      output_index: index,
    });
    push("response.function_call_arguments.done", {
      item_id: item.id,
      name: call.name,
      arguments: argumentsText,
      output_index: index,
    });
    push("response.output_item.done", { output_index: index, item });
    return item;
  });
  push("response.completed", { response: responseEnvelope(responseId, "completed", items) });
  return frames.join("");
}

/** 只有所有 Tool 结果都返回 Provider 后，才生成已提交的答复。 */
function finalStream(responseId, marker, text) {
  const messageId = `message_${responseId}`;
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
      delta: `${text} ${marker}`,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text: `${text} ${marker}`,
    }),
    event("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [
        { ...message, content: [{ ...message.content[0], text: `${text} ${marker}` }] },
      ]),
    }),
  ].join("");
}

/** 模拟 max-output 截断，并确保不完整的写入调用不会被执行。 */
function truncatedWriteStream() {
  const completeArguments = JSON.stringify({
    path: "must-not-exist.txt",
    content: "TRUNCATED_SHOULD_NOT_WRITE",
  });
  const message = {
    id: "message_truncated_write",
    type: "message",
    role: "assistant",
    status: "incomplete",
    content: [{ type: "output_text", text: "partial response" }],
  };
  const item = {
    id: "item_call_truncated_write",
    type: "function_call",
    call_id: "call_truncated_write",
    name: "write",
    arguments: completeArguments,
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope("resp_truncated_write", "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: "partial response",
      item_id: message.id,
      output_index: 0,
    }),
    event("response.output_item.added", 2, {
      output_index: 1,
      item: { ...item, arguments: "" },
    }),
    event("response.function_call_arguments.delta", 3, {
      item_id: item.id,
      delta: completeArguments,
      output_index: 1,
    }),
    event("response.incomplete", 4, {
      response: {
        ...responseEnvelope("resp_truncated_write", "incomplete", [message, item], false),
        incomplete_details: { reason: "max_output_tokens" },
      },
    }),
  ].join("");
}

/** 读取一条有界请求；loopback 服务端不保留任意模型上下文。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("Provider request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 仅提取决定下一条确定性 fixture 响应所需的调用结果。 */
function outputsByCall(payload) {
  const outputs = Array.isArray(payload?.input)
    ? payload.input.filter(
        (item) => item?.type === "function_call_output" && typeof item.call_id === "string",
      )
    : [];
  return new Map(outputs.map((item) => [item.call_id, item.output]));
}

/** 按最新的用户消息路由，避免历史标记误匹配同一 Thread 中后续的 Turn。 */
function latestUserHasMarker(payload, marker) {
  if (typeof payload?.input === "string") return payload.input.includes(marker);
  const entries = Array.isArray(payload?.input) ? payload.input : [];
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index];
    if (entry?.role !== "user") continue;
    const content = entry.content;
    if (typeof content === "string") return content.includes(marker);
    return (
      Array.isArray(content) &&
      content.some((part) => typeof part?.text === "string" && part.text.includes(marker))
    );
  }
  return false;
}

/** 仅当 Provider 续传未携带先前用户文本时，才回退到唯一 Tool 调用标识。 */
function scenarioFor(payload, outputs) {
  if (latestUserHasMarker(payload, MARKERS.deadline)) return "deadline";
  if (latestUserHasMarker(payload, MARKERS.correction)) return "correction";
  if (latestUserHasMarker(payload, MARKERS.truncation)) return "truncation";
  if (outputs.has("call_truncated_write") || outputs.has("call_recovered_write"))
    return "truncation";
  if (outputs.has("call_invalid_write") || outputs.has("call_corrected_write")) return "correction";
  if (Object.values(MAIN_CALLS).some((call) => outputs.has(call.callId))) return "deadline";
  return null;
}

/** 仅记录可见工具名和 JSON 属性名，用于将 fixture 调用与实时目录核对。 */
function exposedToolCatalog(payload) {
  return (Array.isArray(payload?.tools) ? payload.tools : [])
    .filter((tool) => typeof tool?.name === "string")
    .map((tool) => ({
      name: tool.name,
      properties: Object.keys(
        tool.parameters?.properties ?? tool.input_schema?.properties ?? {},
      ).sort(),
    }));
}

/** 返回稳定的事实摘要，不把路径、参数或文件内容复制到报告中。 */
function outputFacts(outputs, expected) {
  return expected.map((call) => {
    const value = outputs.get(call.callId);
    return {
      callId: call.callId,
      present: typeof value === "string" && value.length > 0,
      containsExpectedText: typeof value === "string" && value.includes(call.expected),
    };
  });
}

/** 仅在 loopback fixture 内等待；每次延迟响应都短于对应 Provider 超时。 */
function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/** 启动确定性的 Responses 服务，并以有界路由状态覆盖完整编码工具流程。 */
export async function startCodingAgentDeadlineProvider({ holdEditResponse = false } = {}) {
  const observations = [];
  let failure = null;
  let signalEditResponseReady;
  let releaseEditResponse;
  let editResponseReleased = false;
  const editResponseReady = new Promise((resolve) => {
    signalEditResponseReady = resolve;
  });
  const editResponseGate = new Promise((resolve) => {
    releaseEditResponse = resolve;
  });

/** 等真实 read/search 结果到达后，等待下一条编辑响应进入挂起状态。 */
  function waitForEditResponseBarrier(timeoutMs = 30_000) {
    if (!holdEditResponse) {
      return Promise.reject(new Error("read-result UI barrier is disabled for this fixture"));
    }
    let timer;
    return Promise.race([
      editResponseReady,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("read-result UI barrier timed out")), timeoutMs);
      }),
    ]).finally(() => clearTimeout(timer));
  }

/** 释放挂起的 Provider 响应；保持幂等也便于失败清理安全调用。 */
  function releaseEditResponseBarrier() {
    if (!holdEditResponse || editResponseReleased) return;
    editResponseReleased = true;
    releaseEditResponse();
  }

/** 等待 HTTP 服务关闭前，先释放仍在等待的界面屏障。 */
  async function close() {
    releaseEditResponseBarrier();
    await new Promise((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }

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
      const serialized = JSON.stringify(payload?.input ?? []);
      if (serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")) {
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        response.end(finalStream("resp_coding_agent_title", "", "Coding Agent acceptance"));
        return;
      }

      const outputs = outputsByCall(payload);
      const scenario = scenarioFor(payload, outputs);
      const responseCount = observations.length + 1;
      if (scenario === "deadline") {
        if (!outputs.has(MAIN_CALLS.write.callId)) {
          observations.push({
            scenario: "deadline",
            responseCount,
            stage: "write",
            toolCatalog: exposedToolCatalog(payload),
          });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(toolStream("resp_deadline_write", [MAIN_CALLS.write]));
          return;
        }
        if (!READ_CALLS.every((call) => outputs.has(call.callId))) {
          await delay(RESPONSE_DELAY_MS);
          observations.push({ scenario: "deadline", responseCount, stage: "read_search_batch" });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(toolStream("resp_deadline_read_search", READ_CALLS));
          return;
        }
        if (!outputs.has(MAIN_CALLS.edit.callId)) {
          const checks = outputFacts(outputs, [
            { ...MAIN_CALLS.read, expected: "ORIGINAL_MARKER" },
            { ...MAIN_CALLS.grep, expected: "ORIGINAL_MARKER" },
            { ...MAIN_CALLS.find, expected: "deadline-proof.txt" },
            { ...MAIN_CALLS.ls, expected: "deadline-proof.txt" },
          ]);
          observations.push({ scenario: "deadline", responseCount, stage: "edit", checks });
          if (holdEditResponse) {
            signalEditResponseReady();
            await editResponseGate;
          } else {
            await delay(RESPONSE_DELAY_MS);
          }
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(toolStream("resp_deadline_edit", [MAIN_CALLS.edit]));
          return;
        }
        if (!outputs.has(MAIN_CALLS.shell.callId)) {
          observations.push({ scenario: "deadline", responseCount, stage: "exec_command" });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(toolStream("resp_deadline_shell", [MAIN_CALLS.shell]));
          return;
        }
        observations.push({
          scenario: "deadline",
          responseCount,
          stage: "final",
          shellSawEditedContent: String(outputs.get(MAIN_CALLS.shell.callId)).includes(
            "EDITED_MARKER",
          ),
        });
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        response.end(
          finalStream(
            "resp_deadline_final",
            "CODING_AGENT_DEADLINE_FINAL_OK",
            "Workspace changes verified.",
          ),
        );
        return;
      }

      if (scenario === "correction") {
        if (!outputs.has("call_invalid_write")) {
          observations.push({ scenario: "correction", responseCount, stage: "invalid_write" });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(
            toolStream("resp_invalid_write", [
              {
                callId: "call_invalid_write",
                name: "write",
                arguments: { path: "must-not-exist-invalid.txt", content: 42 },
              },
            ]),
          );
          return;
        }
        if (!outputs.has("call_corrected_write")) {
          observations.push({
            scenario: "correction",
            responseCount,
            stage: "corrected_write",
            invalidCallReturned: typeof outputs.get("call_invalid_write") === "string",
          });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(
            toolStream("resp_corrected_write", [
              {
                callId: "call_corrected_write",
                name: "write",
                arguments: { path: "corrected.txt", content: "CORRECTED_WRITE_OK\n" },
              },
            ]),
          );
          return;
        }
        observations.push({ scenario: "correction", responseCount, stage: "final" });
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        response.end(
          finalStream(
            "resp_correction_final",
            "CODING_AGENT_CORRECTION_FINAL_OK",
            "Invalid arguments corrected.",
          ),
        );
        return;
      }

      if (scenario === "truncation") {
        if (!outputs.has("call_truncated_write")) {
          observations.push({ scenario: "truncation", responseCount, stage: "truncated_write" });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(truncatedWriteStream());
          return;
        }
        if (!outputs.has("call_recovered_write")) {
          observations.push({
            scenario: "truncation",
            responseCount,
            stage: "recovered_write",
            truncatedCallReturned: typeof outputs.get("call_truncated_write") === "string",
          });
          response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
          response.end(
            toolStream("resp_recovered_after_truncation", [
              {
                callId: "call_recovered_write",
                name: "write",
                arguments: {
                  path: "truncation-recovered.txt",
                  content: "TRUNCATION_RECOVERED_OK\n",
                },
              },
            ]),
          );
          return;
        }
        observations.push({ scenario: "truncation", responseCount, stage: "final" });
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        response.end(
          finalStream(
            "resp_truncation_final",
            "CODING_AGENT_TRUNCATION_FINAL_OK",
            "Truncated call recovered.",
          ),
        );
        return;
      }
      throw new Error("unexpected Provider request outside a coding-agent acceptance Turn");
    } catch (error) {
      failure = String(error?.message ?? error).slice(0, 240);
      if (!response.headersSent) {
        response.writeHead(500, { "content-type": "application/json" });
        response.end('{"error":"coding_agent_fixture_failed"}');
      } else {
        response.destroy();
      }
    }
  });

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    observations,
    requestTimeoutMs: REQUEST_TIMEOUT_MS,
    responseDelayMs: RESPONSE_DELAY_MS,
    failure: () => failure,
    waitForEditResponseBarrier,
    releaseEditResponseBarrier,
    close,
  };
}

export const codingAgentDeadlineMarkers = MARKERS;
