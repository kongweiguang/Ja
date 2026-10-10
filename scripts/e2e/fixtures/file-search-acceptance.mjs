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
const AGENT_LOOP_MARKERS = Object.freeze({
  clarification: Object.freeze({
    user: "JA_CLARIFICATION_ACCEPTANCE_USER",
    answer: "cerulean",
    final: "JA_CLARIFICATION_ACCEPTANCE_FINAL_OK",
    callId: "call_clarification_acceptance",
  }),
  approval: Object.freeze({
    user: "JA_APPROVAL_ACCEPTANCE_USER",
    callId: "call_approval_acceptance",
    command: "Start-Sleep -Seconds 30",
  }),
  mailbox: Object.freeze({
    user: "JA_MAILBOX_ACCEPTANCE_TURN_START",
    idle: "JA_MAILBOX_ACCEPTANCE_IDLE_MESSAGE",
    active: "JA_MAILBOX_ACCEPTANCE_ACTIVE_MESSAGE",
    callId: "call_mailbox_acceptance_ls",
    final: "JA_MAILBOX_ACCEPTANCE_FINAL_OK",
  }),
});

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

/** 构造一条延迟的 fixture 响应，让 runner 能在 Provider 调用尚未结束时排入新消息。 */
function deferred() {
  let resolve;
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
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

/** 只按最后一条 user 输入分流，避免持久历史 marker 抢占后续 Agent Loop 请求。 */
function latestUserText(input) {
  if (!Array.isArray(input)) return typeof input === "string" ? input : undefined;
  for (let index = input.length - 1; index >= 0; index -= 1) {
    const message = input[index];
    if (message === null || typeof message !== "object" || message.role !== "user") continue;
    if (typeof message.content === "string") return message.content;
    if (!Array.isArray(message.content)) continue;
    return message.content
      .filter(
        (block) => block !== null && typeof block === "object" && typeof block.text === "string",
      )
      .map((block) => block.text)
      .join("\n");
  }
  return undefined;
}

/** 即使队列消息排在最后，也按唯一 mailbox 主 Turn marker 识别当前 Provider user 历史。 */
function hasUserMarker(input, marker) {
  if (!Array.isArray(input)) return typeof input === "string" && input.includes(marker);
  return input.some((message) => {
    if (message === null || typeof message !== "object" || message.role !== "user") return false;
    if (typeof message.content === "string") return message.content.includes(marker);
    return (
      Array.isArray(message.content) &&
      message.content.some(
        (block) =>
          block !== null &&
          typeof block === "object" &&
          typeof block.text === "string" &&
          block.text.includes(marker),
      )
    );
  });
}

/** 只统计 Provider 输入中 mailbox marker 的位置布尔值，诊断时不保存任何正文。 */
function mailboxMarkerFacts(payload) {
  const input = payload?.input;
  const entries = Array.isArray(input) ? input : [];
  const users = entries.filter((item) => item !== null && typeof item === "object" && item.role === "user");
  const systemEntries = entries.filter(
    (item) => item !== null && typeof item === "object" && ["system", "developer"].includes(item.role),
  );
  const nonUserEntries = entries.filter(
    (item) =>
      item !== null &&
      typeof item === "object" &&
      !["user", "system", "developer"].includes(item.role),
  );
  const latest = latestUserText(input) ?? "";
  const anyUserText = JSON.stringify(users);
  const systemText = [payload?.instructions, payload?.system, ...systemEntries]
    .map((value) => (typeof value === "string" ? value : JSON.stringify(value ?? "")))
    .join("\n");
  const nonUserText = JSON.stringify(nonUserEntries);
  const markers = AGENT_LOOP_MARKERS.mailbox;
  const factsFor = (marker) => ({
    latestUser: latest.includes(marker),
    anyUser: anyUserText.includes(marker) || (typeof input === "string" && input.includes(marker)),
    system: systemText.includes(marker),
    nonUser: nonUserText.includes(marker),
  });
  return {
    user: factsFor(markers.user),
    idle: factsFor(markers.idle),
    active: factsFor(markers.active),
  };
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

/** 返回带稳定标记的最终答复，供运行时门禁核实具体恢复场景。 */
function markedFinalStream(responseId, messageId, text) {
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

/** 使用生产适配器相同的 Responses 生命周期编码一次 fixture Tool 调用。 */
function singleToolStream(responseId, callId, toolName, argumentsValue) {
  const itemId = `item_${callId}`;
  const argumentsText = JSON.stringify(argumentsValue);
  const item = {
    id: itemId,
    type: "function_call",
    call_id: callId,
    name: toolName,
    arguments: argumentsText,
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_item.added", 1, {
      output_index: 0,
      item: { ...item, arguments: "" },
    }),
    event("response.function_call_arguments.delta", 2, {
      item_id: itemId,
      delta: argumentsText,
      output_index: 0,
    }),
    event("response.function_call_arguments.done", 3, {
      item_id: itemId,
      name: toolName,
      arguments: argumentsText,
      output_index: 0,
    }),
    event("response.output_item.done", 4, { output_index: 0, item }),
    event("response.completed", 5, {
      response: responseEnvelope(responseId, "completed", [item]),
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

/** 以 loopback 固定 Provider 响应覆盖真实请求分支，并用可控挂起确定运行中 Mailbox 的投递时序。 */
export async function startFileSearchFixture() {
  const attempts = [];
  const requestRoutingFacts = [];
  let failure = null;
  const mailboxRequestSeen = deferred();
  const fixtureRequestFailed = deferred();
  const mailboxResponseGate = deferred();
  let mailboxResponseReleased = false;
  let mailboxResponseAborted = false;
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
      const currentUserText = latestUserText(payload?.input) ?? "";
      const hasFileSearchCall = (Array.isArray(payload?.input) ? payload.input : []).some(
        (item) =>
          item?.type === "function_call" && CALLS.some((call) => call.callId === item.call_id),
      );
      requestRoutingFacts.push({
        currentUserHasFileSearchMarker: currentUserText.includes(USER_MARKER),
        hasFileSearchCall,
        mailbox: mailboxMarkerFacts(payload),
      });
      if (requestRoutingFacts.length > 32) requestRoutingFacts.shift();
      const toolNames = exposedToolNames(payload);
      const isTitle = serializedInput.includes("<user_request>") && serializedInput.includes("<assistant_reply>");
      if (isTitle) {
        attempts.push({ kind: "title" });
        sendStream(response, titleStream());
        return;
      }
      if (currentUserText.includes(AGENT_LOOP_MARKERS.clarification.user)) {
        const answerOutputs = outputs.filter(
          (item) => item.call_id === AGENT_LOOP_MARKERS.clarification.callId,
        );
        if (answerOutputs.length === 0) {
          assert.ok(
            toolNames.includes("request_user_input"),
            "clarification Tool is missing from the Provider tool catalog",
          );
          attempts.push({ kind: "clarification_request", toolNames: [...toolNames] });
          sendStream(
            response,
            singleToolStream(
              "resp_clarification_question",
              AGENT_LOOP_MARKERS.clarification.callId,
              "request_user_input",
              {
                questions: [
                  {
                    questionId: "question_color",
                    prompt: "Which color?",
                    type: "text",
                    options: [],
                    required: true,
                    allowFreeText: true,
                  },
                ],
              },
            ),
          );
          return;
        }
        if (
          answerOutputs.length !== 1 ||
          typeof answerOutputs[0].output !== "string" ||
          answerOutputs[0].output.length === 0
        ) {
          throw new Error("clarification continuation did not return its one answer result");
        }
        const answerSeen = serializedInput.includes(AGENT_LOOP_MARKERS.clarification.answer);
        if (!answerSeen) throw new Error("clarification answer was missing from the resumed Provider request");
        attempts.push({
          kind: "clarification_resumption",
          answerSeen: true,
          outputCount: answerOutputs.length,
        });
        sendStream(
          response,
          markedFinalStream(
            "resp_clarification_final",
            "message_clarification_final",
            `Clarification applied. ${AGENT_LOOP_MARKERS.clarification.final}`,
          ),
        );
        return;
      }
      if (currentUserText.includes(AGENT_LOOP_MARKERS.approval.user)) {
        const approvalOutputs = outputs.filter(
          (item) => item.call_id === AGENT_LOOP_MARKERS.approval.callId,
        );
        if (approvalOutputs.length === 0) {
          assert.ok(toolNames.includes("exec_command"), "shell Tool is missing from the Provider tool catalog");
          attempts.push({ kind: "approval_request", toolNames: [...toolNames] });
          sendStream(
            response,
            singleToolStream(
              "resp_approval_request",
              AGENT_LOOP_MARKERS.approval.callId,
              "exec_command",
              { cmd: AGENT_LOOP_MARKERS.approval.command },
            ),
          );
          return;
        }
        if (
          approvalOutputs.length !== 1 ||
          typeof approvalOutputs[0].output !== "string" ||
          approvalOutputs[0].output.length === 0
        ) {
          throw new Error("approval continuation did not return its one shell result");
        }
        attempts.push({ kind: "approval_continuation", outputCount: approvalOutputs.length });
        sendStream(
          response,
          markedFinalStream(
            "resp_approval_final",
            "message_approval_final",
            "Shell tool returned.",
          ),
        );
        return;
      }
      if (hasUserMarker(payload?.input, AGENT_LOOP_MARKERS.mailbox.user)) {
        const mailboxOutputs = outputs.filter(
          (item) => item.call_id === AGENT_LOOP_MARKERS.mailbox.callId,
        );
        const isMailboxContinuation = mailboxOutputs.length > 0;
        if (!isMailboxContinuation) {
          assert.ok(
            serializedInput.includes(AGENT_LOOP_MARKERS.mailbox.idle),
            "message queued while idle was missing from the first model request",
          );
          assert.ok(toolNames.includes("ls"), "ls Tool is missing from the mailbox Provider request");
          if (attempts.some((attempt) => attempt.kind === "mailbox_initial")) {
            throw new Error("mailbox fixture received a second initial request");
          }
          const attempt = { kind: "mailbox_initial", responseAborted: false };
          attempts.push(attempt);
          response.on("close", () => {
            if (!response.writableEnded) {
              attempt.responseAborted = true;
              mailboxResponseAborted = true;
            }
          });
          mailboxRequestSeen.resolve();
          await mailboxResponseGate.promise;
          if (response.destroyed) return;
          sendStream(
            response,
            singleToolStream(
              "resp_mailbox_tool",
              AGENT_LOOP_MARKERS.mailbox.callId,
              "ls",
              { path: ".", maxEntries: 20 },
            ),
          );
          return;
        }
        if (
          mailboxOutputs.length !== 1 ||
          typeof mailboxOutputs[0].output !== "string" ||
          mailboxOutputs[0].output.length === 0
        ) {
          throw new Error("mailbox Tool continuation did not return its one ls result");
        }
        const activeMessageSeen = serializedInput.includes(AGENT_LOOP_MARKERS.mailbox.active);
        if (!activeMessageSeen) {
          throw new Error("message sent to the running Thread was missing from the next Provider request");
        }
        attempts.push({
          kind: "mailbox_next_request",
          activeMessageSeen: true,
          outputCount: mailboxOutputs.length,
        });
        sendStream(
          response,
          markedFinalStream(
            "resp_mailbox_final",
            "message_mailbox_final",
            `Mailbox message received. ${AGENT_LOOP_MARKERS.mailbox.final}`,
          ),
        );
        return;
      }
      if (currentUserText.includes(USER_MARKER) || hasFileSearchCall) {
        const fileSearchOutputs = outputs.filter((item) =>
          CALLS.some((call) => call.callId === item.call_id),
        );
        if (fileSearchOutputs.length === 0) {
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
        const parsed = parseFileSearchOutputs({ input: fileSearchOutputs });
        const facts = assertFileSearchOutputs(parsed);
        attempts.push({
          kind: "continuation",
          outputCount: parsed.size,
          outputCharacters: facts.outputCharacters,
        });
        sendStream(response, finalStream());
        return;
      }
      if (outputs.length === 0) {
        throw new Error("unexpected Provider request without a file search or Agent Loop acceptance marker");
      }
      throw new Error("Provider continuation did not match a supported fixture scenario");
    } catch (error) {
      failure = {
        category: "fixture",
        message: String(error?.message ?? error).slice(0, 240),
        requestRoutingFacts: requestRoutingFacts.at(-1) ?? null,
      };
      fixtureRequestFailed.resolve(failure);
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
          answerSeen: attempt.answerSeen,
          activeMessageSeen: attempt.activeMessageSeen,
          responseAborted: attempt.responseAborted,
        })),
        requestRoutingFacts: requestRoutingFacts.map((route) => ({
          currentUserHasFileSearchMarker: route.currentUserHasFileSearchMarker,
          hasFileSearchCall: route.hasFileSearchCall,
          mailbox: Object.fromEntries(
            Object.entries(route.mailbox).map(([marker, facts]) => [marker, { ...facts }]),
          ),
        })),
        failure:
          failure === null
            ? null
            : {
                category: failure.category,
                message: failure.message,
                requestRoutingFacts:
                  failure.requestRoutingFacts === null
                    ? null
                    : {
                        currentUserHasFileSearchMarker:
                          failure.requestRoutingFacts.currentUserHasFileSearchMarker,
                        hasFileSearchCall: failure.requestRoutingFacts.hasFileSearchCall,
                        mailbox: Object.fromEntries(
                          Object.entries(failure.requestRoutingFacts.mailbox).map(([marker, facts]) => [
                            marker,
                            { ...facts },
                          ]),
                        ),
                      },
              },
        mailboxResponseAborted,
      };
    },
/** fixture 首次拒绝请求时立即失败，避免邮箱等待直到超时才暴露路由错误。 */
    async waitForMailboxRequest(timeoutMs = 30_000) {
      let timer;
      try {
        const outcome = await Promise.race([
          mailboxRequestSeen.promise.then(() => ({ kind: "opened" })),
          fixtureRequestFailed.promise.then((reason) => ({ kind: "failed", reason })),
          new Promise((resolvePromise) => {
            timer = setTimeout(() => resolvePromise({ kind: "timeout" }), timeoutMs);
          }),
        ]);
        if (outcome.kind === "failed") {
          throw new Error(`mailbox Provider request failed in fixture: ${outcome.reason.message}`);
        }
        if (outcome.kind === "timeout") throw new Error("mailbox Provider request was not observed");
      } finally {
        clearTimeout(timer);
      }
    },
/** runner 排入实时消息后，释放邮箱中唯一挂起的 Provider 响应。 */
    releaseMailboxResponse() {
      if (mailboxResponseReleased) throw new Error("mailbox Provider response was already released");
      mailboxResponseReleased = true;
      mailboxResponseGate.resolve();
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

export const agentLoopFixtureMarkers = AGENT_LOOP_MARKERS;
