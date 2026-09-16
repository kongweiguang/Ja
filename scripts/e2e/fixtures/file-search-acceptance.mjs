// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

const CALLS = Object.freeze([
  Object.freeze({ callId: "call_file_search_ls", name: "ls", arguments: { path: ".", maxEntries: 200 } }),
  Object.freeze({
    callId: "call_file_search_readme",
    name: "find",
    arguments: { pattern: "README*", path: ".", limit: 200 },
  }),
  Object.freeze({
    callId: "call_file_search_agents",
    name: "find",
    arguments: { pattern: "AGENTS.md", path: ".", limit: 200 },
  }),
  Object.freeze({
    callId: "call_file_search_codegraph",
    name: "find",
    arguments: { pattern: ".codegraph", path: ".", limit: 200 },
  }),
]);

const FINAL_MARKER = "JA_FILE_SEARCH_ACCEPTANCE_FINAL_OK";
const USER_MARKER = "JA_FILE_SEARCH_ACCEPTANCE_USER";

/** 将一个 Responses SSE 事件编码为 Java OpenAI Responses adapter 可解析的帧。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequenceNumber,
    ...payload,
  })}\n\n`;
}

/** 构造满足当前 JA-RPC Provider adapter 最小字段闭集的 Responses envelope。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "file-search-acceptance",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 64,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 24,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 88,
    };
  }
  return response;
}

/** 从当前 Provider 请求中取出工具名，保持 fixture 对 JA Responses wire 形状的严格约束。 */
function exposedToolNames(payload) {
  return (Array.isArray(payload?.tools) ? payload.tools : [])
    .map((definition) => definition?.name)
    .filter((name) => typeof name === "string");
}

/** 读取有界 JSON 请求体；fixture 不保存完整用户输入、工具 Schema 或路径正文。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("file search request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 从 continuation 读取所有 function_call_output，并拒绝缺失或重复的 Tool 结果。 */
export function parseFileSearchOutputs(payload) {
  const outputs = Array.isArray(payload?.input)
    ? payload.input.filter(
        (item) => item?.type === "function_call_output" && typeof item.call_id === "string",
      )
    : [];
  const byCallId = new Map();
  for (const output of outputs) {
    if (byCallId.has(output.call_id)) throw new Error("duplicate file search Tool result");
    if (typeof output.output !== "string" || output.output.length === 0) {
      throw new Error("file search Tool result must be non-empty text");
    }
    byCallId.set(output.call_id, output.output);
  }
  if (byCallId.size !== CALLS.length || CALLS.some((call) => !byCallId.has(call.callId))) {
    throw new Error("file search continuation must contain all four Tool results");
  }
  return byCallId;
}

/** 验证四个搜索结果的可观察事实，尤其保证 ignored .venv 没有污染返回正文。 */
export function assertFileSearchOutputs(outputs) {
  const byCallId = outputs instanceof Map ? outputs : parseFileSearchOutputs({ input: outputs });
  const lsOutput = byCallId.get(CALLS[0].callId);
  const readmeOutput = byCallId.get(CALLS[1].callId);
  const agentsOutput = byCallId.get(CALLS[2].callId);
  const codegraphOutput = byCallId.get(CALLS[3].callId);
  assert.match(lsOutput, /README\.md/u, "ls must expose the root README");
  assert.match(lsOutput, /AGENTS\.md/u, "ls must expose the root AGENTS");
  assert.match(lsOutput, /\.codegraph/u, "ls must expose the .codegraph directory");
  assert.match(readmeOutput, /README\.md/u, "README glob must return a path");
  assert.match(agentsOutput, /AGENTS\.md/u, "AGENTS glob must return a path");
  assert.match(codegraphOutput, /\.codegraph/u, ".codegraph glob must return a path");
  for (const output of byCallId.values()) {
    assert.doesNotMatch(output, /\.venv[\\/]/u, "ignored .venv must not enter Tool output");
  }
  return {
    callCount: byCallId.size,
    outputCharacters: [...byCallId.values()].reduce((total, value) => total + value.length, 0),
  };
}

/** 生成同一 assistant message 中的 ls 与三个 find function_call，覆盖一次 Tool batch。 */
function searchToolStream() {
  const messageId = "message_file_search_tools";
  const text = "开始检查工作区目录和关键说明文件。";
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  const items = CALLS.map((call, index) => ({
    id: `item_${call.callId}`,
    type: "function_call",
    call_id: call.callId,
    name: call.name,
    arguments: JSON.stringify(call.arguments),
    output_index: index + 1,
  }));
  const frames = [];
  let sequenceNumber = 0;
  frames.push(
    event("response.created", sequenceNumber++, {
      response: responseEnvelope("resp_file_search_tools", "in_progress", [], false),
    }),
  );
  frames.push(
    event("response.output_text.delta", sequenceNumber++, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", sequenceNumber++, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
  );
  for (const item of items) {
    frames.push(
      event("response.output_item.added", sequenceNumber++, {
        output_index: item.output_index,
        item: { ...item, arguments: "" },
      }),
      event("response.function_call_arguments.done", sequenceNumber++, {
        item_id: item.id,
        arguments: item.arguments,
        output_index: item.output_index,
      }),
      event("response.output_item.done", sequenceNumber++, {
        output_index: item.output_index,
        item,
      }),
    );
  }
  frames.push(
    event("response.completed", sequenceNumber++, {
      response: responseEnvelope("resp_file_search_tools", "completed", [message, ...items]),
    }),
  );
  return frames.join("");
}

/** 生成只含最终正文的 continuation response，证明所有四个 Tool 结果先回到 Provider。 */
function finalStream() {
  const messageId = "message_file_search_final";
  const text = `工作区检查完成，四个文件工具结果均已返回。${FINAL_MARKER}`;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope("resp_file_search_final", "in_progress", [], false),
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
      response: responseEnvelope("resp_file_search_final", "completed", [message]),
    }),
  ].join("");
}

/** 生成自动标题的普通文本响应，避免后台标题请求污染文件 Tool continuation。 */
function titleStream() {
  const messageId = "message_file_search_title";
  const text = "文件搜索验收";
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope("resp_file_search_title", "in_progress", [], false),
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
      response: responseEnvelope("resp_file_search_title", "completed", [message]),
    }),
  ].join("");
}

/** 将完整 SSE 一次性返回，避免测试 fixture 的传输节奏改变工具执行调度。 */
function sendStream(response, stream) {
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": "text/event-stream; charset=utf-8",
    connection: "close",
  });
  response.end(stream);
}

/** 启动仅监听 loopback 的确定性 Provider，严格要求四个 Tool 结果全部续传后才给最终答复。 */
export async function startFileSearchFixture() {
  const attempts = [];
  let failure = null;
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const serializedInput = JSON.stringify(payload?.input ?? []);
      const outputs = (Array.isArray(payload?.input) ? payload.input : []).filter(
        (item) => item?.type === "function_call_output",
      );
      const toolNames = exposedToolNames(payload);
      const isTitle = serializedInput.includes("<user_request>") && serializedInput.includes("<assistant_reply>");
      if (isTitle) {
        attempts.push({ kind: "title" });
        sendStream(response, titleStream());
        return;
      }
      if (outputs.length === 0) {
        if (!serializedInput.includes(USER_MARKER)) {
          throw new Error("unexpected Provider request without file search acceptance marker");
        }
        if (attempts.some((attempt) => attempt.kind === "initial")) {
          throw new Error("file search fixture received a second initial request");
        }
        for (const name of ["find", "ls"]) {
          assert.ok(toolNames.includes(name), `${name} is missing from the Provider tool catalog`);
        }
        attempts.push({ kind: "initial", toolNames: [...toolNames], callIds: CALLS.map((call) => call.callId) });
        sendStream(response, searchToolStream());
        return;
      }
      if (attempts.some((attempt) => attempt.kind === "continuation")) {
        throw new Error("file search fixture received a second continuation request");
      }
      const parsed = parseFileSearchOutputs(payload);
      const facts = assertFileSearchOutputs(parsed);
      attempts.push({
        kind: "continuation",
        outputCount: parsed.size,
        outputCharacters: facts.outputCharacters,
      });
      sendStream(response, finalStream());
    } catch (error) {
      failure = { category: "fixture", message: String(error?.message ?? error).slice(0, 240) };
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "file_search_fixture_failure" }));
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
    /** 只返回有限 Provider 阶段和数量，避免把请求正文写进诊断。 */
    snapshot() {
      return {
        attempts: attempts.map((attempt) => ({
          kind: attempt.kind,
          toolNames: attempt.toolNames === undefined ? undefined : [...attempt.toolNames],
          callIds: attempt.callIds === undefined ? undefined : [...attempt.callIds],
          outputCount: attempt.outputCount,
          outputCharacters: attempt.outputCharacters,
        })),
        failure: failure === null ? null : { ...failure },
      };
    },
    /** 关闭本轮唯一 loopback listener，避免 fixture 生命周期越过 runner 清理边界。 */
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

export const fileSearchFixtureMarkers = Object.freeze({
  final: FINAL_MARKER,
  user: USER_MARKER,
  calls: CALLS.map((call) => ({
    callId: call.callId,
    name: call.name,
    arguments: { ...call.arguments },
  })),
});
