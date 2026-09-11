// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 三种 Provider 协议的 loopback SSE fixture。
 *
 * Fixture 只生成协议级事件并记录脱敏请求事实，真实的消息归约、JA-RPC、SQLite 和 UI
 * 仍由 Java App Server 与 Tauri/WebView2 承担。private marker 只用于验证下一轮原生续传，
 * runner 会确认它们没有进入用户可见 DOM。
 */

import { createServer } from "node:http";

const ALL_PROTOCOLS = Object.freeze([
  "openai_responses",
  "openai_chat_completions",
  "anthropic_messages",
]);

const TOOL_CALLS = Object.freeze({
  openai_responses: Object.freeze({
    read: "call_reasoning_responses_read",
    shell: "call_reasoning_responses_shell",
  }),
  openai_chat_completions: Object.freeze({
    read: "call_reasoning_chat_read",
    shell: "call_reasoning_chat_shell",
  }),
  anthropic_messages: Object.freeze({
    read: "call_reasoning_anthropic_read",
    shell: "call_reasoning_anthropic_shell",
  }),
});

const REASONING_MARKERS = Object.freeze({
  openai_responses: Object.freeze([
    "JA_REASONING_RESPONSES_1",
    "JA_REASONING_RESPONSES_2",
    "JA_REASONING_RESPONSES_3",
    "JA_REASONING_RESPONSES_FOLLOWUP",
    "JA_REASONING_RESPONSES_RESTART",
  ]),
  openai_chat_completions: Object.freeze([
    "JA_REASONING_CHAT_1",
    "JA_REASONING_CHAT_2",
    "JA_REASONING_CHAT_3",
    "JA_REASONING_CHAT_FOLLOWUP",
    "JA_REASONING_CHAT_RESTART",
  ]),
  anthropic_messages: Object.freeze([
    "JA_REASONING_ANTHROPIC_1",
    "JA_REASONING_ANTHROPIC_2",
    "JA_REASONING_ANTHROPIC_3",
    "JA_REASONING_ANTHROPIC_FOLLOWUP",
    "JA_REASONING_ANTHROPIC_RESTART",
  ]),
});

const FINAL_MARKERS = Object.freeze({
  openai_responses: Object.freeze([
    "JA_REASONING_FINAL_RESPONSES_1",
    "JA_REASONING_FINAL_RESPONSES_2",
    "JA_REASONING_FINAL_RESPONSES_3",
    "JA_REASONING_FINAL_RESPONSES_FOLLOWUP",
    "JA_REASONING_FINAL_RESPONSES_RESTART",
  ]),
  openai_chat_completions: Object.freeze([
    "JA_REASONING_FINAL_CHAT_1",
    "JA_REASONING_FINAL_CHAT_2",
    "JA_REASONING_FINAL_CHAT_3",
    "JA_REASONING_FINAL_CHAT_FOLLOWUP",
    "JA_REASONING_FINAL_CHAT_RESTART",
  ]),
  anthropic_messages: Object.freeze([
    "JA_REASONING_FINAL_ANTHROPIC_1",
    "JA_REASONING_FINAL_ANTHROPIC_2",
    "JA_REASONING_FINAL_ANTHROPIC_3",
    "JA_REASONING_FINAL_ANTHROPIC_FOLLOWUP",
    "JA_REASONING_FINAL_ANTHROPIC_RESTART",
  ]),
});

const PRIVATE_MARKERS = Object.freeze({
  openai_responses: Object.freeze({
    encrypted: Object.freeze([
      "JA_PRIVATE_RESPONSES_ENCRYPTED_1",
      "JA_PRIVATE_RESPONSES_ENCRYPTED_2",
      "JA_PRIVATE_RESPONSES_ENCRYPTED_3",
      "JA_PRIVATE_RESPONSES_ENCRYPTED_FOLLOWUP",
      "JA_PRIVATE_RESPONSES_ENCRYPTED_RESTART",
    ]),
  }),
  openai_chat_completions: Object.freeze({
    // Chat Completions 的 reasoning_content 是可展示正文，同时必须以原字段回传；它没有
    // Responses encrypted_content 或 Anthropic signature 那样的第二个私有 marker。
  }),
  anthropic_messages: Object.freeze({
    signature: Object.freeze([
      "JA_PRIVATE_ANTHROPIC_SIGNATURE_1",
      "JA_PRIVATE_ANTHROPIC_SIGNATURE_2",
      "JA_PRIVATE_ANTHROPIC_SIGNATURE_3",
      "JA_PRIVATE_ANTHROPIC_SIGNATURE_FOLLOWUP",
      "JA_PRIVATE_ANTHROPIC_SIGNATURE_RESTART",
    ]),
    redacted: Object.freeze([
      "JA_PRIVATE_ANTHROPIC_REDACTED_1",
      "JA_PRIVATE_ANTHROPIC_REDACTED_2",
      "JA_PRIVATE_ANTHROPIC_REDACTED_3",
      "JA_PRIVATE_ANTHROPIC_REDACTED_FOLLOWUP",
      "JA_PRIVATE_ANTHROPIC_REDACTED_RESTART",
    ]),
  }),
});

const PATHS = Object.freeze({
  openai_responses: "/v1/responses",
  openai_chat_completions: "/v1/chat/completions",
  anthropic_messages: "/v1/messages",
});

/**
 * 返回 runner 与合同测试共享的固定公开/私有 marker；对象冻结避免测试通过修改 fixture
 * 预期值绕过真实请求与 DOM 断言。
 */
export function reasoningFixtureContract(protocol) {
  if (!ALL_PROTOCOLS.includes(protocol)) throw new Error(`unknown reasoning protocol: ${protocol}`);
  return Object.freeze({
    protocol,
    path: PATHS[protocol],
    toolCalls: TOOL_CALLS[protocol],
    reasoningMarkers: REASONING_MARKERS[protocol],
    finalMarkers: FINAL_MARKERS[protocol],
    finalMarker: FINAL_MARKERS[protocol][0],
    privateMarkers: PRIVATE_MARKERS[protocol],
  });
}

/** 从固定合同取指定 assistant response 的 marker，越界即失败，避免测试静默复用历史 identity。 */
function markerAt(values, index, label) {
  const marker = values?.[index];
  if (typeof marker !== "string" || marker.length === 0)
    throw new Error(`${label} marker ${index} is unavailable`);
  return marker;
}

/** 将 OpenAI Responses 事件编码为带 event/data 的严格 SSE 帧。 */
function openAiEvent(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequenceNumber,
    ...payload,
  })}\n\n`;
}

/** 将 OpenAI Chat Completions chunk 编码为标准 data-only SSE 帧。 */
function chatChunk(id, model, choices, usage) {
  const payload = {
    id,
    object: "chat.completion.chunk",
    created: 0,
    model,
    choices,
  };
  if (usage !== undefined) payload.usage = usage;
  return `data: ${JSON.stringify(payload)}\n\n`;
}

/** 将 Anthropic Messages 事件编码为显式 event/data SSE 帧，避免宽松解析掩盖协议错误。 */
function anthropicEvent(type, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, ...payload })}\n\n`;
}

/** 构造 Responses 的终态 envelope，字段闭集与 Java adapter 的 shape 校验保持一致。 */
function responseEnvelope(responseId, model, output, status, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model,
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 24,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 18,
      output_tokens_details: { reasoning_tokens: 7 },
      total_tokens: 42,
    };
  }
  return response;
}

/** 构造 OpenAI Responses 的 reasoning item；encrypted_content 永远只作为原生续传状态。 */
function responsesReasoningItem(contract, responseIndex) {
  const marker = markerAt(contract.reasoningMarkers, responseIndex, "Responses reasoning");
  return {
    id: `reasoning_${contract.protocol}_${responseIndex + 1}`,
    type: "reasoning",
    summary: [{ type: "summary_text", text: marker }],
    encrypted_content: markerAt(
      contract.privateMarkers.encrypted,
      responseIndex,
      "Responses encrypted",
    ),
  };
}

/** 构造 Responses function_call item，参数使用内建 Tool 的稳定 schema。 */
function responsesToolItem(contract, responseIndex, name, argumentsValue) {
  const callId = contract.toolCalls[name];
  const itemId = `item_${contract.protocol}_${name}_${responseIndex + 1}`;
  return {
    id: itemId,
    type: "function_call",
    call_id: callId,
    name,
    arguments: JSON.stringify(argumentsValue),
  };
}

/** 构造 Responses 的一轮推理摘要、Tool 或最终文本流。 */
function responsesStream(contract, body, round, responseIndex) {
  const responseId = `resp_${contract.protocol}_${responseIndex + 1}`;
  const model = typeof body?.model === "string" ? body.model : "reasoning-loopback";
  const reasoning = responsesReasoningItem(contract, responseIndex);
  const frames = [
    openAiEvent("response.created", 0, {
      response: responseEnvelope(responseId, model, [], "in_progress", false),
    }),
    openAiEvent("response.output_item.added", 1, {
      output_index: 0,
      item: {
        ...reasoning,
        summary: [],
        encrypted_content: markerAt(
          contract.privateMarkers.encrypted,
          responseIndex,
          "Responses encrypted",
        ),
      },
    }),
    openAiEvent("response.reasoning_summary_text.delta", 2, {
      delta: markerAt(contract.reasoningMarkers, responseIndex, "Responses reasoning"),
      item_id: reasoning.id,
      output_index: 0,
      summary_index: 0,
    }),
    openAiEvent("response.reasoning_summary_text.done", 3, {
      item_id: reasoning.id,
      output_index: 0,
      summary_index: 0,
      text: markerAt(contract.reasoningMarkers, responseIndex, "Responses reasoning"),
    }),
    openAiEvent("response.output_item.done", 4, { output_index: 0, item: reasoning }),
  ];
  if (round < 2) {
    const name = round === 0 ? "read" : "shell";
    const argumentsValue =
      name === "read"
        ? { path: "reasoning-fixture.txt", offset: 1, limit: 32 }
        : { command: "Write-Output JA_REASONING_TOOL_OK", timeout_ms: 5_000 };
    const item = responsesToolItem(contract, responseIndex, name, argumentsValue);
    frames.push(
      openAiEvent("response.output_item.added", 5, {
        output_index: 1,
        item: { ...item, arguments: "" },
      }),
      openAiEvent("response.function_call_arguments.done", 6, {
        item_id: item.id,
        arguments: item.arguments,
        output_index: 1,
      }),
      openAiEvent("response.output_item.done", 7, { output_index: 1, item }),
      openAiEvent("response.completed", 8, {
        response: responseEnvelope(responseId, model, [reasoning, item], "completed"),
      }),
    );
  } else {
    const message = {
      id: `message_${contract.protocol}_final_${responseIndex + 1}`,
      type: "message",
      role: "assistant",
      status: "completed",
      content: [
        {
          type: "output_text",
          text: markerAt(contract.finalMarkers, responseIndex, "Responses final"),
          annotations: [],
          logprobs: [],
        },
      ],
    };
    frames.push(
      openAiEvent("response.output_text.delta", 5, {
        content_index: 0,
        delta: markerAt(contract.finalMarkers, responseIndex, "Responses final"),
        item_id: message.id,
        output_index: 1,
        logprobs: [],
      }),
      openAiEvent("response.output_text.done", 6, {
        content_index: 0,
        item_id: message.id,
        output_index: 1,
        text: markerAt(contract.finalMarkers, responseIndex, "Responses final"),
      }),
      openAiEvent("response.output_item.done", 7, { output_index: 1, item: message }),
      openAiEvent("response.completed", 8, {
        response: responseEnvelope(responseId, model, [reasoning, message], "completed"),
      }),
    );
  }
  return frames.join("");
}

/** 构造 Chat Completions 的 reasoning_content、Tool delta 或最终文本流。 */
function chatStream(contract, body, round, responseIndex) {
  const id = `chatcmpl_${contract.protocol}_${responseIndex + 1}`;
  const model = typeof body?.model === "string" ? body.model : "reasoning-loopback";
  const marker = markerAt(contract.reasoningMarkers, responseIndex, "Chat reasoning");
  const frames = [
    chatChunk(id, model, [
      {
        index: 0,
        delta: { role: "assistant", reasoning_content: marker },
        finish_reason: null,
      },
    ]),
  ];
  if (round < 2) {
    const name = round === 0 ? "read" : "shell";
    const argumentsValue =
      name === "read"
        ? JSON.stringify({ path: "reasoning-fixture.txt", offset: 1, limit: 32 })
        : JSON.stringify({ command: "Write-Output JA_REASONING_TOOL_OK", timeout_ms: 5_000 });
    const callId = contract.toolCalls[name];
    const split = Math.max(1, Math.floor(argumentsValue.length / 2));
    frames.push(
      chatChunk(id, model, [
        {
          index: 0,
          delta: {
            tool_calls: [
              {
                index: 0,
                id: callId,
                type: "function",
                function: { name, arguments: argumentsValue.slice(0, split) },
              },
            ],
          },
          finish_reason: null,
        },
      ]),
      chatChunk(
        id,
        model,
        [
          {
            index: 0,
            delta: {
              tool_calls: [{ index: 0, function: { arguments: argumentsValue.slice(split) } }],
            },
            finish_reason: "tool_calls",
          },
        ],
        {
          prompt_tokens: 24,
          completion_tokens: 18,
          total_tokens: 42,
          completion_tokens_details: { reasoning_tokens: 7 },
        },
      ),
      "data: [DONE]\n\n",
    );
  } else {
    frames.push(
      chatChunk(
        id,
        model,
        [
          {
            index: 0,
            delta: { content: markerAt(contract.finalMarkers, responseIndex, "Chat final") },
            finish_reason: "stop",
          },
        ],
        {
          prompt_tokens: 24,
          completion_tokens: 18,
          total_tokens: 42,
          completion_tokens_details: { reasoning_tokens: 7 },
        },
      ),
      "data: [DONE]\n\n",
    );
  }
  return frames.join("");
}

/** 构造 Anthropic thinking、签名、redacted block、Tool 或最终文本流。 */
function anthropicStream(contract, body, round, responseIndex) {
  const model = typeof body?.model === "string" ? body.model : "reasoning-loopback";
  const marker = markerAt(contract.reasoningMarkers, responseIndex, "Anthropic reasoning");
  const messageId = `msg_${contract.protocol}_${responseIndex + 1}`;
  const frames = [
    anthropicEvent("message_start", {
      message: {
        id: messageId,
        type: "message",
        role: "assistant",
        content: [],
        model,
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 24, output_tokens: 0 },
      },
    }),
    anthropicEvent("content_block_start", {
      index: 0,
      content_block: { type: "thinking", thinking: "" },
    }),
    anthropicEvent("content_block_delta", {
      index: 0,
      delta: { type: "thinking_delta", thinking: marker },
    }),
    anthropicEvent("content_block_delta", {
      index: 0,
      delta: {
        type: "signature_delta",
        signature: markerAt(
          contract.privateMarkers.signature,
          responseIndex,
          "Anthropic signature",
        ),
      },
    }),
    anthropicEvent("content_block_stop", { index: 0 }),
  ];
  if (round < 2) {
    const name = round === 0 ? "read" : "shell";
    const input =
      name === "read"
        ? { path: "reasoning-fixture.txt", offset: 1, limit: 32 }
        : { command: "Write-Output JA_REASONING_TOOL_OK", timeout_ms: 5_000 };
    const callId = contract.toolCalls[name];
    const toolIndex = 2;
    frames.push(
      anthropicEvent("content_block_start", {
        index: 1,
        content_block: {
          type: "redacted_thinking",
          data: markerAt(contract.privateMarkers.redacted, responseIndex, "Anthropic redacted"),
        },
      }),
      anthropicEvent("content_block_stop", { index: 1 }),
      anthropicEvent("content_block_start", {
        index: toolIndex,
        content_block: { type: "tool_use", id: callId, name, input },
      }),
      anthropicEvent("content_block_stop", { index: toolIndex }),
      anthropicEvent("message_delta", {
        delta: { stop_reason: "tool_use", stop_sequence: null },
        usage: { output_tokens: 18 },
      }),
      anthropicEvent("message_stop", {}),
    );
  } else {
    frames.push(
      anthropicEvent("content_block_start", {
        index: 1,
        content_block: {
          type: "redacted_thinking",
          data: markerAt(contract.privateMarkers.redacted, responseIndex, "Anthropic redacted"),
        },
      }),
      anthropicEvent("content_block_stop", { index: 1 }),
      anthropicEvent("content_block_start", {
        index: 2,
        content_block: { type: "text", text: "" },
      }),
      anthropicEvent("content_block_delta", {
        index: 2,
        delta: {
          type: "text_delta",
          text: markerAt(contract.finalMarkers, responseIndex, "Anthropic final"),
        },
      }),
      anthropicEvent("content_block_stop", { index: 2 }),
      anthropicEvent("message_delta", {
        delta: { stop_reason: "end_turn", stop_sequence: null },
        usage: { output_tokens: 18 },
      }),
      anthropicEvent("message_stop", {}),
    );
  }
  return frames.join("");
}

/** 生成标题请求的无 reasoning 响应，避免后台标题请求污染三轮主 Turn 断言。 */
function titleStream(protocol, body) {
  const title = `Reasoning ${protocol}`;
  if (protocol === "openai_responses") {
    const id = "resp_reasoning_title";
    const message = {
      id: "message_reasoning_title",
      type: "message",
      role: "assistant",
      status: "completed",
      content: [{ type: "output_text", text: title, annotations: [], logprobs: [] }],
    };
    return [
      openAiEvent("response.created", 0, {
        response: responseEnvelope(
          id,
          body?.model ?? "reasoning-loopback",
          [],
          "in_progress",
          false,
        ),
      }),
      openAiEvent("response.output_text.delta", 1, {
        content_index: 0,
        delta: title,
        item_id: message.id,
        output_index: 0,
        logprobs: [],
      }),
      openAiEvent("response.output_text.done", 2, {
        content_index: 0,
        item_id: message.id,
        output_index: 0,
        text: title,
      }),
      openAiEvent("response.completed", 3, {
        response: responseEnvelope(id, body?.model ?? "reasoning-loopback", [message], "completed"),
      }),
    ].join("");
  }
  if (protocol === "openai_chat_completions") {
    const id = "chatcmpl_reasoning_title";
    return [
      chatChunk(
        id,
        body?.model ?? "reasoning-loopback",
        [{ index: 0, delta: { role: "assistant", content: title }, finish_reason: "stop" }],
        { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 },
      ),
      "data: [DONE]\n\n",
    ].join("");
  }
  const id = "msg_reasoning_title";
  return [
    anthropicEvent("message_start", {
      message: {
        id,
        type: "message",
        role: "assistant",
        content: [],
        model: body?.model ?? "reasoning-loopback",
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 3, output_tokens: 0 },
      },
    }),
    anthropicEvent("content_block_start", {
      index: 0,
      content_block: { type: "text", text: "" },
    }),
    anthropicEvent("content_block_delta", { index: 0, delta: { type: "text_delta", text: title } }),
    anthropicEvent("content_block_stop", { index: 0 }),
    anthropicEvent("message_delta", {
      delta: { stop_reason: "end_turn", stop_sequence: null },
      usage: { output_tokens: 2 },
    }),
    anthropicEvent("message_stop", {}),
  ].join("");
}

/** 有界读取 JSON body；fixture 不保留原始 prompt、Tool 参数或认证 header。 */
async function readBoundedJson(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > 8 * 1024 * 1024) throw new Error("reasoning fixture request exceeded 8 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 用 provider-neutral call id 判断当前模型轮次，只检查历史身份不保存完整输入。 */
function requestRound(contract, payload) {
  const serialized = JSON.stringify(payload ?? {});
  if (serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")) {
    return { kind: "title", round: undefined };
  }
  if (serialized.includes(contract.toolCalls.shell)) return { kind: "turn", round: 2 };
  if (serialized.includes(contract.toolCalls.read)) return { kind: "turn", round: 1 };
  return { kind: "turn", round: 0 };
}

/** 对原生字段数组做闭集顺序比对，防止只命中一个历史 marker 就被误认为完整续传。 */
function sameOrderedValues(actual, expected) {
  return (
    actual.length === expected.length && actual.every((value, index) => value === expected[index])
  );
}

/** 从 OpenAI Responses input 中提取 reasoning 原生条目，保留 summary 与 encrypted_content 的配对顺序。 */
function responsesContinuationValues(payload) {
  const input = Array.isArray(payload?.input) ? payload.input : [];
  return input
    .filter((item) => item?.type === "reasoning")
    .map((item) => ({
      summary: item?.summary?.[0]?.text,
      encrypted: item?.encrypted_content,
    }));
}

/** 从 Chat Completions assistant history 中提取原字段 reasoning_content 的出现顺序。 */
function chatContinuationValues(payload) {
  const messages = Array.isArray(payload?.messages) ? payload.messages : [];
  return messages
    .filter(
      (message) =>
        message?.role === "assistant" &&
        Object.prototype.hasOwnProperty.call(message, "reasoning_content"),
    )
    .map((message) => message.reasoning_content);
}

/** 从 Anthropic assistant content 中提取 thinking/signature 与 redacted_thinking 的原生块顺序。 */
function anthropicContinuationValues(payload) {
  const messages = Array.isArray(payload?.messages) ? payload.messages : [];
  return messages
    .filter((message) => message?.role === "assistant")
    .flatMap((message) => (Array.isArray(message.content) ? message.content : []))
    .filter((block) => block?.type === "thinking" || block?.type === "redacted_thinking")
    .map((block) =>
      block.type === "thinking"
        ? { type: "thinking", thinking: block.thinking, signature: block.signature }
        : { type: "redacted_thinking", data: block.data },
    );
}

/** 检查本轮请求是否按 Provider 原生字段完整、按序回传全部历史 reasoning，并返回脱敏事实。 */
function privateContinuation(contract, payload, responseIndex) {
  if (responseIndex === 0) {
    return {
      required: false,
      seen: false,
      continuationSequenceVerified: true,
      continuationCount: 0,
      encryptedSeen: false,
      signatureSeen: false,
      redactedSeen: false,
      reasoningSeen: false,
    };
  }
  const expectedReasoning = contract.reasoningMarkers.slice(0, responseIndex);
  let continuationCount = 0;
  let encryptedSeen = false;
  let signatureSeen = false;
  let redactedSeen = false;
  let reasoningSeen = false;
  let continuationSequenceVerified = false;
  if (contract.protocol === "openai_responses") {
    const actual = responsesContinuationValues(payload);
    const expectedEncrypted = contract.privateMarkers.encrypted.slice(0, responseIndex);
    continuationCount = actual.length;
    reasoningSeen = sameOrderedValues(
      actual.map((item) => item.summary),
      expectedReasoning,
    );
    encryptedSeen = sameOrderedValues(
      actual.map((item) => item.encrypted),
      expectedEncrypted,
    );
    continuationSequenceVerified = reasoningSeen && encryptedSeen;
  } else if (contract.protocol === "openai_chat_completions") {
    const actual = chatContinuationValues(payload);
    continuationCount = actual.length;
    reasoningSeen = sameOrderedValues(actual, expectedReasoning);
    continuationSequenceVerified = reasoningSeen;
  } else {
    const actual = anthropicContinuationValues(payload);
    const expectedSignature = contract.privateMarkers.signature.slice(0, responseIndex);
    const expectedRedacted = contract.privateMarkers.redacted.slice(0, responseIndex);
    const expectedBlocks = expectedReasoning.flatMap((reasoning, index) => [
      { type: "thinking", thinking: reasoning, signature: expectedSignature[index] },
      { type: "redacted_thinking", data: expectedRedacted[index] },
    ]);
    continuationCount = actual.filter((block) => block.type === "thinking").length;
    reasoningSeen = sameOrderedValues(
      actual.filter((block) => block.type === "thinking").map((block) => block.thinking),
      expectedReasoning,
    );
    signatureSeen = sameOrderedValues(
      actual.filter((block) => block.type === "thinking").map((block) => block.signature),
      expectedSignature,
    );
    redactedSeen = sameOrderedValues(
      actual.filter((block) => block.type === "redacted_thinking").map((block) => block.data),
      expectedRedacted,
    );
    continuationSequenceVerified =
      actual.length === expectedBlocks.length &&
      actual.every(
        (block, index) =>
          block.type === expectedBlocks[index].type &&
          (block.type === "thinking"
            ? block.thinking === expectedBlocks[index].thinking &&
              block.signature === expectedBlocks[index].signature
            : block.data === expectedBlocks[index].data),
      );
  }
  return {
    required: true,
    seen: continuationSequenceVerified,
    continuationSequenceVerified,
    continuationCount,
    encryptedSeen,
    signatureSeen,
    redactedSeen,
    reasoningSeen,
  };
}

/** 只向 loopback 客户端延迟发送 SSE，先让 runner 观察 live reasoning，再放行 Tool/final。 */
async function writeDelayedStream(response, stream, state, gateKey) {
  const body = Buffer.from(stream, "utf8");
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": "text/event-stream; charset=utf-8",
    connection: "close",
  });
  const frames = body.toString("utf8").split("\n\n").filter(Boolean);
  for (const frame of frames) {
    response.write(`${frame}\n\n`);
    const dataLine = frame.split("\n").find((line) => line.startsWith("data:"));
    let event;
    try {
      event = dataLine === undefined ? undefined : JSON.parse(dataLine.slice(5).trim());
    } catch {
      event = undefined;
    }
    const eventType = event?.type;
    if (
      eventType === "response.reasoning_summary_text.delta" ||
      (eventType === "content_block_delta" && event?.delta?.type === "thinking_delta") ||
      (Array.isArray(event?.choices) &&
        event.choices.some((choice) => typeof choice?.delta?.reasoning_content === "string"))
    ) {
      state.stages.push(`reasoning_${gateKey}`);
      const gate = state.gates.get(gateKey);
      if (gate === undefined) throw new Error("reasoning fixture gate unavailable");
      // deferred 返回的是带 release 的 owner；必须等待其 promise，不能把控制对象本身当作 Thenable。
      await gate.promise;
    }
    if (
      eventType === "response.function_call_arguments.done" ||
      (eventType === "content_block_start" && event?.content_block?.type === "tool_use") ||
      (Array.isArray(event?.choices) &&
        event.choices.some((choice) => Array.isArray(choice?.delta?.tool_calls)))
    ) {
      state.stages.push(`tool_${gateKey}`);
    }
    if (
      eventType === "response.output_text.delta" ||
      (eventType === "content_block_delta" && event?.delta?.type === "text_delta") ||
      (Array.isArray(event?.choices) &&
        event.choices.some((choice) => typeof choice?.delta?.content === "string"))
    )
      state.stages.push(`final_${gateKey}`);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 45));
  }
  response.end();
  state.stages.push(`completed_${gateKey}`);
}

/** 创建一个有界 deferred，close 时统一释放，避免失败验收留下挂起 HTTP 响应。 */
function deferred() {
  let resolvePromise;
  const promise = new Promise((resolveValue) => {
    resolvePromise = resolveValue;
  });
  return { promise, release: () => resolvePromise() };
}

/** 启动只监听 IPv4 loopback 的三协议 Provider fixture。 */
export async function startReasoningProviderFixture({ protocols = ALL_PROTOCOLS } = {}) {
  const allowed = [...protocols];
  for (const protocol of allowed) reasoningFixtureContract(protocol);
  const attempts = [];
  const stages = [];
  const gates = new Map();
  for (const protocol of allowed) {
    for (let responseIndex = 0; responseIndex < 5; responseIndex += 1) {
      gates.set(`${protocol}:${responseIndex}`, deferred());
    }
  }
  const responseCounts = new Map(allowed.map((protocol) => [protocol, 0]));
  const state = { attempts, stages, gates };
  const server = createServer(async (request, response) => {
    const protocol = allowed.find((candidate) => request.url === PATHS[candidate]);
    if (request.method !== "POST" || protocol === undefined) {
      response.writeHead(404, { "content-type": "application/json", connection: "close" });
      response.end('{"error":"not_found"}');
      return;
    }
    const contract = reasoningFixtureContract(protocol);
    try {
      const payload = await readBoundedJson(request);
      const exchange = requestRound(contract, payload);
      const responseIndex = exchange.kind === "turn" ? responseCounts.get(protocol) : undefined;
      if (exchange.kind === "turn" && responseIndex === undefined)
        throw new Error("reasoning fixture protocol state unavailable");
      const continuation = privateContinuation(contract, payload, responseIndex ?? 0);
      const attempt = {
        protocol,
        kind: exchange.kind,
        round: exchange.round,
        responseIndex,
        privateContinuationRequired: continuation.required,
        privateContinuationSeen: continuation.seen,
        continuationSequenceVerified: continuation.continuationSequenceVerified,
        continuationCount: continuation.continuationCount,
        encryptedSeen: continuation.encryptedSeen,
        signatureSeen: continuation.signatureSeen,
        redactedSeen: continuation.redactedSeen,
        reasoningSeen: continuation.reasoningSeen,
      };
      attempts.push(attempt);
      if (exchange.kind === "turn" && continuation.required && !continuation.seen) {
        response.writeHead(409, { "content-type": "application/json", connection: "close" });
        response.end('{"error":"native_reasoning_continuation_missing"}');
        return;
      }
      const stream =
        exchange.kind === "title"
          ? titleStream(protocol, payload)
          : protocol === "openai_responses"
            ? responsesStream(contract, payload, exchange.round, responseIndex)
            : protocol === "openai_chat_completions"
              ? chatStream(contract, payload, exchange.round, responseIndex)
              : anthropicStream(contract, payload, exchange.round, responseIndex);
      if (exchange.kind === "turn") responseCounts.set(protocol, responseIndex + 1);
      if (exchange.kind === "title") {
        response.writeHead(200, {
          "cache-control": "no-store",
          "content-type": "text/event-stream; charset=utf-8",
          connection: "close",
        });
        response.end(stream);
      } else {
        await writeDelayedStream(response, stream, state, `${protocol}:${responseIndex}`);
      }
    } catch (error) {
      response.writeHead(400, { "content-type": "application/json", connection: "close" });
      response.end(JSON.stringify({ error: String(error?.message ?? error).slice(0, 200) }));
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("fixture port unavailable");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    stages,
    /** 释放某一协议/assistant response 的 live reasoning gate，使 Tool 或最终正文继续流入真实 adapter。 */
    release(protocol, responseIndex) {
      const gate = gates.get(`${protocol}:${responseIndex}`);
      if (gate === undefined)
        throw new Error(`unknown reasoning gate: ${protocol}:${responseIndex}`);
      gate.release();
    },
    /** 释放全部 gate，供失败路径和 server cleanup 使用。 */
    releaseAll() {
      for (const gate of gates.values()) gate.release();
    },
    /** 只返回脱敏的请求/阶段事实，不返回任何 prompt、Tool 参数或 header。 */
    snapshot() {
      return {
        attempts: attempts.map((attempt) => ({ ...attempt })),
        stages: [...stages],
      };
    },
    /** 关闭唯一 loopback listener，并释放所有未完成响应。 */
    async close() {
      for (const gate of gates.values()) gate.release();
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

export { ALL_PROTOCOLS, FINAL_MARKERS, PATHS, PRIVATE_MARKERS, REASONING_MARKERS, TOOL_CALLS };
