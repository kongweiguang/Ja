// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  committedTerminalReply,
  createIsolatedDirectories,
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";
import { createLargePngFixture } from "./image-history-production.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const placeholderSecret = "media-schema-loopback-placeholder";
const requestTimeoutMs = 60_000;
const answerSchema = Object.freeze({
  type: "object",
  properties: {
    answer: { type: "string", enum: ["JA_SCHEMA_OK"] },
  },
  required: ["answer"],
  additionalProperties: false,
});
const apis = Object.freeze([
  Object.freeze({
    api: "openai_responses",
    route: "/v1/responses",
    id: "responses",
    model: "gpt-4o-mini",
    providerId: "provider_media_responses",
    modelId: "model_media_responses",
    credentialId: "cred_media_schema_responses",
  }),
  Object.freeze({
    api: "openai_chat_completions",
    route: "/v1/chat/completions",
    id: "chat",
    model: "gpt-4o-mini",
    providerId: "provider_media_chat",
    modelId: "model_media_chat",
    credentialId: "cred_media_schema_chat",
  }),
  Object.freeze({
    api: "anthropic_messages",
    route: "/v1/messages",
    id: "anthropic",
    model: "claude-3-5-sonnet-20241022",
    providerId: "provider_media_anthropic",
    modelId: "model_media_anthropic",
    credentialId: "cred_media_schema_anthropic",
  }),
]);

/** 只接受 Native executable 覆盖参数；默认启动仍由受 JDK 25 校验的 JAR 路径负责。 */
export function parseLaunchArguments(args) {
  let executable;
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--executable") {
      if (executable !== undefined) throw new Error("--executable may be specified only once");
      const value = args[index + 1];
      if (typeof value !== "string" || value.trim() === "" || value.startsWith("--")) {
        throw new Error("--executable requires a path");
      }
      executable = value;
      index += 1;
      continue;
    }
    if (argument.startsWith("--executable=")) {
      if (executable !== undefined) throw new Error("--executable may be specified only once");
      const value = argument.slice("--executable=".length);
      if (value.trim() === "") throw new Error("--executable requires a path");
      executable = value;
      continue;
    }
    throw new Error("unsupported media/schema loopback argument: " + argument);
  }
  return { executable };
}

/** 三种协议共用对象根 Schema，避免 fixture 只验证某一家的结构化输出形状。 */
export function outputSchema() {
  return structuredClone(answerSchema);
}

/** 按各 Provider 编码器实际落点取 Schema，防止测试检查到请求旁路字段。 */
function requestSchema(api, payload) {
  if (api === "openai_responses") return payload?.text?.format?.schema;
  if (api === "openai_chat_completions") return payload?.response_format?.json_schema?.schema;
  if (api === "anthropic_messages") return payload?.output_config?.format?.schema;
  return undefined;
}

/** 只提取可公开的 Schema 事实，Provider 请求正文和图片字节不进入验收报告。 */
function schemaEvidence(api, payload) {
  const format =
    api === "openai_responses"
      ? payload?.text?.format
      : api === "openai_chat_completions"
        ? payload?.response_format
        : payload?.output_config?.format;
  const schema = requestSchema(api, payload);
  const answer = schema?.properties?.answer;
  return {
    declared:
      format?.type === "json_schema" &&
      schema?.type === "object" &&
      Array.isArray(schema?.required) &&
      schema.required.includes("answer") &&
      Array.isArray(answer?.enum) &&
      answer.enum.includes("JA_SCHEMA_OK"),
    type: typeof schema?.type === "string" ? schema.type : null,
    required: Array.isArray(schema?.required)
      ? schema.required.filter((value) => typeof value === "string").slice(0, 32)
      : [],
    answerEnum: Array.isArray(answer?.enum)
      ? answer.enum.filter((value) => typeof value === "string").slice(0, 32)
      : [],
  };
}

/** 同一 Thread 保留历史 marker；同位置前缀冲突时选择更长的精确场景 marker。 */
export function latestScenario(payload, markers) {
  const serialized = JSON.stringify(payload);
  let latest = { scenario: "auxiliary", position: -1, markerLength: 0 };
  for (const [scenario, marker] of markers) {
    const position = serialized.lastIndexOf(marker);
    if (
      position >= 0 &&
      (position > latest.position ||
        (position === latest.position && marker.length > latest.markerLength))
    ) {
      latest = { scenario, position, markerLength: marker.length };
    }
  }
  return latest.scenario;
}

/** 将各协议图片块解码到临时 Buffer，以便逐字节比对而不持久化媒体内容。 */
function imageBlockBytes(node) {
  if (node?.type === "input_image" && typeof node.image_url === "string") {
    const match = /^data:(image\/[a-z0-9.+-]+);base64,([A-Za-z0-9+/]+={0,2})$/iu.exec(
      node.image_url,
    );
    if (match === null) return undefined;
    return { mediaType: match[1].toLowerCase(), bytes: Buffer.from(match[2], "base64") };
  }
  if (
    node?.type === "image" &&
    node.source?.type === "base64" &&
    typeof node.source.media_type === "string" &&
    typeof node.source.data === "string"
  ) {
    return {
      mediaType: node.source.media_type.toLowerCase(),
      bytes: Buffer.from(node.source.data, "base64"),
    };
  }
  return undefined;
}

/** 同时识别用户输入和 Tool 结果媒体，但不保留 Provider 请求树或媒体字节。 */
function collectImages(value, images = [], inToolResult = false) {
  if (Array.isArray(value)) {
    for (const item of value) collectImages(item, images, inToolResult);
    return images;
  }
  if (value === null || typeof value !== "object") return images;
  const toolResult =
    inToolResult || value.type === "tool_result" || value.type === "function_call_output";
  const image = imageBlockBytes(value);
  if (image !== undefined) images.push({ ...image, inToolResult: toolResult });
  for (const child of Object.values(value)) collectImages(child, images, toolResult);
  return images;
}

/**
 * 在内存中精确比较媒体字节，调用方只获得计数与摘要匹配事实，避免输出图片正文。
 */
export function inspectProtocolRequest(
  api,
  payload,
  expectedImageBytes,
  knownImageBytes = [expectedImageBytes],
) {
  const images = collectImages(payload);
  const toolImages = images.filter((image) => image.inToolResult);
  const expected = Buffer.from(expectedImageBytes);
  const expectedDigest = createHash("sha256").update(expected).digest("hex");
  const knownDigests = new Set(
    knownImageBytes.map((bytes) => createHash("sha256").update(Buffer.from(bytes)).digest("hex")),
  );
  const toolImageDigests = toolImages.map((image) =>
    createHash("sha256").update(image.bytes).digest("hex"),
  );
  const outputSchema = schemaEvidence(api, payload);
  return {
    schemaDeclared: outputSchema.declared,
    schema: outputSchema,
    imageCount: images.length,
    toolImageCount: toolImages.length,
    matchingExpectedToolImageCount: toolImages.filter(
      (image, index) =>
        image.mediaType === "image/png" && toolImageDigests[index] === expectedDigest,
    ).length,
    toolImageDigests,
    toolImageDigestMatches:
      toolImages.length > 0 &&
      toolImageDigests.every(
        (digest, index) => toolImages[index].mediaType === "image/png" && knownDigests.has(digest),
      ) &&
      toolImageDigests.includes(expectedDigest),
    toolImageLimitNoticePresent: hasMarker(payload, "Image not sent to this model"),
  };
}

/** 递归抽取文本用于 fixture 分流，原始 Provider 请求对象仅留在当前调用作用域。 */
function hasMarker(value, marker) {
  if (typeof value === "string") return value.includes(marker);
  if (Array.isArray(value)) return value.some((item) => hasMarker(item, marker));
  if (value !== null && typeof value === "object") {
    return Object.values(value).some((item) => hasMarker(item, marker));
  }
  return false;
}

/** 适配三种协议各自的 Tool 目录外形，不复制或长期保留请求载荷。 */
function toolDefinitions(api, payload) {
  if (!Array.isArray(payload?.tools)) return [];
  if (api === "openai_chat_completions") {
    return payload.tools.map((tool) => ({
      name: tool?.function?.name,
      schema: tool?.function?.parameters,
    }));
  }
  if (api === "anthropic_messages") {
    return payload.tools.map((tool) => ({ name: tool?.name, schema: tool?.input_schema }));
  }
  return payload.tools.map((tool) => ({ name: tool?.name, schema: tool?.parameters }));
}

/** 按验收场景选定内置读图或固定 MCP 网关，避免把不同媒体来源混作同一能力。 */
function imageToolCall(api, payload, callId, scenario) {
  if (scenario.startsWith("mcp")) {
    const tool = toolDefinitions(api, payload).find((candidate) => candidate.name === "mcp");
    if (tool === undefined) throw new Error("provider did not receive the MCP gateway tool");
    return {
      callId,
      name: tool.name,
      arguments: {
        action: "call",
        serverId: "mcp_media_fixture",
        toolName: "read_fixture_image",
        query: null,
        offset: null,
        argumentsJson: JSON.stringify({ corrupt: scenario.startsWith("mcp_invalid") }),
      },
    };
  }
  const candidates = toolDefinitions(api, payload).filter((tool) => tool.name === "view_image");
  if (candidates.length !== 1) throw new Error("provider did not receive one workspace image tool");
  const properties = candidates[0].schema?.properties ?? {};
  const pathName = ["path", "relativePath", "relative_path", "filePath", "file_path"].find((name) =>
    Object.hasOwn(properties, name),
  );
  if (pathName === undefined) throw new Error("workspace image tool has no path argument");
  const path = scenario.startsWith("missing:")
    ? "missing-capability-image.png"
    : scenario.startsWith("read_failure:")
      ? "read-failure"
      : "ja-capability-image.png";
  return { callId, name: candidates[0].name, arguments: { [pathName]: path } };
}

/** 生成符合 Responses 流契约的最小事件，并由调用方保持序号严格递增。 */
function responseEvent(type, sequence, payload) {
  return (
    "event: " +
    type +
    "\ndata: " +
    JSON.stringify({ type, sequence_number: sequence, ...payload }) +
    "\n\n"
  );
}

/** 提供真实流 reducer 必需的 Responses envelope 字段，保持 fixture 与生产解码路径一致。 */
function responsesEnvelope(id, status, output, usage = true) {
  const response = {
    id,
    created_at: 0,
    model: "ja-loopback",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (usage) {
    response.usage = {
      input_tokens: 32,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 12,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 44,
    };
  }
  return response;
}

/** 使用 Responses 原生 function_call 项驱动真实 Agent 循环执行图片读取 Tool。 */
function responsesToolStream(callId, name, args) {
  const id = "resp_" + callId;
  const encoded = JSON.stringify(args);
  const item = {
    id: "item_" + callId,
    type: "function_call",
    call_id: callId,
    name,
    arguments: encoded,
  };
  return [
    responseEvent("response.created", 0, {
      response: responsesEnvelope(id, "in_progress", [], false),
    }),
    responseEvent("response.output_item.added", 1, {
      output_index: 0,
      item: { ...item, arguments: "" },
    }),
    responseEvent("response.function_call_arguments.delta", 2, {
      item_id: item.id,
      delta: encoded,
      output_index: 0,
    }),
    responseEvent("response.function_call_arguments.done", 3, {
      item_id: item.id,
      name,
      arguments: encoded,
      output_index: 0,
    }),
    responseEvent("response.output_item.done", 4, { output_index: 0, item }),
    responseEvent("response.completed", 5, {
      response: responsesEnvelope(id, "completed", [item]),
    }),
  ].join("");
}

/** 输出完整 Responses 消息终态，避免把仅有流 delta 误判为已提交答案。 */
function responsesTextStream(id, text) {
  const itemId = "message_" + id;
  const message = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    responseEvent("response.created", 0, {
      response: responsesEnvelope(id, "in_progress", [], false),
    }),
    responseEvent("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: itemId,
      output_index: 0,
      logprobs: [],
    }),
    responseEvent("response.output_text.done", 2, {
      content_index: 0,
      item_id: itemId,
      output_index: 0,
      text,
    }),
    responseEvent("response.completed", 3, {
      response: responsesEnvelope(id, "completed", [message]),
    }),
  ].join("");
}

/** 编码一帧 OpenAI Chat 原生 SSE chunk，沿用实际协议的 choices 与 usage 形状。 */
function chatChunk(id, choices, usage) {
  const frame = { id, object: "chat.completion.chunk", created: 0, model: "ja-loopback", choices };
  if (usage !== undefined) frame.usage = usage;
  return "data: " + JSON.stringify(frame) + "\n\n";
}

/** 按真实 chunk 顺序输出 Chat Tool 调用或结构化终答，并以 usage 收束请求。 */
function chatStream(id, call, text) {
  const chunks = [chatChunk(id, [{ index: 0, delta: { role: "assistant" }, finish_reason: null }])];
  if (call !== undefined) {
    const args = JSON.stringify(call.arguments);
    chunks.push(
      chatChunk(id, [
        {
          index: 0,
          delta: {
            tool_calls: [
              {
                index: 0,
                id: call.callId,
                type: "function",
                function: { name: call.name, arguments: "" },
              },
            ],
          },
          finish_reason: null,
        },
      ]),
    );
    chunks.push(
      chatChunk(id, [
        {
          index: 0,
          delta: { tool_calls: [{ index: 0, function: { arguments: args } }] },
          finish_reason: null,
        },
      ]),
    );
    chunks.push(chatChunk(id, [{ index: 0, delta: {}, finish_reason: "tool_calls" }]));
  } else {
    chunks.push(chatChunk(id, [{ index: 0, delta: { content: text }, finish_reason: null }]));
    chunks.push(chatChunk(id, [{ index: 0, delta: {}, finish_reason: "stop" }]));
  }
  chunks.push(chatChunk(id, [], { prompt_tokens: 32, completion_tokens: 12, total_tokens: 44 }));
  chunks.push("data: [DONE]\n\n");
  return chunks.join("");
}

/** 保持 Anthropic Messages 的独立 event 名称和 data envelope，不复用 OpenAI 帧形状。 */
function anthropicEvent(type, payload) {
  return "event: " + type + "\ndata: " + JSON.stringify({ type, ...payload }) + "\n\n";
}

/** 以完整 JSON 参数 delta 生成 Anthropic tool_use，验证 Provider 原生调用解析器。 */
function anthropicToolStream(callId, name, args) {
  const encoded = JSON.stringify(args);
  return [
    anthropicEvent("message_start", {
      message: {
        id: "msg_" + callId,
        type: "message",
        role: "assistant",
        model: "ja-loopback",
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 32 },
      },
    }),
    anthropicEvent("content_block_start", {
      index: 0,
      content_block: { type: "tool_use", id: callId, name, input: {} },
    }),
    anthropicEvent("content_block_delta", {
      index: 0,
      delta: { type: "input_json_delta", partial_json: encoded },
    }),
    anthropicEvent("content_block_stop", { index: 0 }),
    anthropicEvent("message_delta", {
      delta: { stop_reason: "tool_use", stop_sequence: null },
      usage: { output_tokens: 12 },
    }),
    anthropicEvent("message_stop", {}),
  ].join("");
}

/** 以 end_turn 和完整 token receipt 结束 Anthropic 回答，覆盖其独立终态语义。 */
function anthropicTextStream(id, text) {
  return [
    anthropicEvent("message_start", {
      message: {
        id: "msg_" + id,
        type: "message",
        role: "assistant",
        model: "ja-loopback",
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 32 },
      },
    }),
    anthropicEvent("content_block_start", { index: 0, content_block: { type: "text", text: "" } }),
    anthropicEvent("content_block_delta", { index: 0, delta: { type: "text_delta", text } }),
    anthropicEvent("content_block_stop", { index: 0 }),
    anthropicEvent("message_delta", {
      delta: { stop_reason: "end_turn", stop_sequence: null },
      usage: { output_tokens: 12 },
    }),
    anthropicEvent("message_stop", {}),
  ].join("");
}

/** 让每种 Provider 走各自的原生流编码器，禁止用统一伪协议掩盖适配差异。 */
export function providerStream(api, id, call, text) {
  if (api === "openai_responses") {
    return call === undefined
      ? responsesTextStream(id, text)
      : responsesToolStream(call.callId, call.name, call.arguments);
  }
  if (api === "openai_chat_completions") return chatStream(id, call, text);
  return call === undefined
    ? anthropicTextStream(id, text)
    : anthropicToolStream(call.callId, call.name, call.arguments);
}

/** 在 JSON 解析和媒体比对前先限流请求体，避免本机 fixture 无界保留模型上下文。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 8 * 1024 * 1024) throw new Error("Provider fixture request exceeded 8 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 仅监听 loopback 提供三种协议；能力不支持 marker 可暴露意外派发，观测仅保留有界事实。 */
export async function startCapabilityProviderFixture(
  expectedImageBytes,
  expectedMcpImageBytes = expectedImageBytes,
) {
  const observations = [];
  const mcpCalls = [];
  let callOrdinal = 0;
  const markerByScenario = new Map();
  for (const entry of apis) {
    markerByScenario.set("valid:" + entry.id, "JA_CAP_" + entry.id.toUpperCase() + "_SCHEMA_VALID");
    markerByScenario.set(
      "invalid:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_SCHEMA_INVALID",
    );
    markerByScenario.set(
      "invalid_schema:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_SCHEMA_REQUEST_INVALID",
    );
    markerByScenario.set(
      "media:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_WORKSPACE_IMAGE",
    );
    markerByScenario.set("mcp:" + entry.id, "JA_CAP_" + entry.id.toUpperCase() + "_MCP_IMAGE");
    markerByScenario.set(
      "mcp_invalid:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_MCP_IMAGE_INVALID",
    );
    markerByScenario.set(
      "missing:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_IMAGE_MISSING",
    );
    markerByScenario.set(
      "read_failure:" + entry.id,
      "JA_CAP_" + entry.id.toUpperCase() + "_IMAGE_READ_FAILURE",
    );
  }
  markerByScenario.set("unsupported_schema:responses", "JA_CAP_RESPONSES_SCHEMA_UNSUPPORTED");

  const server = createServer(async (request, response) => {
    response.setHeader("cache-control", "no-store");
    response.setHeader("connection", "close");
    if (request.url === "/mcp") {
      try {
        const payload = await readBoundedJson(request);
        if (payload?.id === undefined) {
          response.writeHead(202, { "mcp-protocol-version": "2025-06-18" });
          response.end();
          return;
        }
        let result;
        if (payload.method === "initialize") {
          result = {
            protocolVersion: "2025-06-18",
            capabilities: { tools: {} },
            serverInfo: { name: "Ja media fixture", version: "1" },
          };
        } else if (payload.method === "tools/list") {
          result = {
            tools: [
              {
                name: "read_fixture_image",
                description: "Return the fixed loopback image as typed MCP image content.",
                inputSchema: {
                  type: "object",
                  properties: { corrupt: { type: "boolean" } },
                  required: ["corrupt"],
                  additionalProperties: false,
                },
              },
            ],
          };
        } else if (
          payload.method === "tools/call" &&
          payload.params?.name === "read_fixture_image"
        ) {
          const corrupt = payload.params?.arguments?.corrupt === true;
          mcpCalls.push({ toolName: payload.params.name, corrupt });
          result = {
            content: [
              {
                type: "image",
                mimeType: "image/png",
                data: corrupt
                  ? "invalid-base64"
                  : Buffer.from(expectedMcpImageBytes).toString("base64"),
              },
            ],
            isError: false,
          };
        } else {
          response.writeHead(404, { "content-type": "application/json" });
          response.end('{"error":"mcp_method_not_found"}');
          return;
        }
        response.writeHead(200, {
          "content-type": "application/json",
          "mcp-protocol-version": "2025-06-18",
          "mcp-session-id": "ja-media-fixture",
        });
        response.end(JSON.stringify({ jsonrpc: "2.0", id: payload.id, result }));
      } catch {
        response.writeHead(400, { "content-type": "application/json" });
        response.end('{"error":"mcp_fixture_rejected_request"}');
      }
      return;
    }
    const api = apis.find((entry) => entry.route === request.url);
    if (request.method !== "POST" || api === undefined) {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const scenario = latestScenario(payload, markerByScenario);
      const expectedScenarioImage =
        scenario.startsWith("mcp:") || scenario.startsWith("mcp_invalid:")
          ? expectedMcpImageBytes
          : expectedImageBytes;
      const evidence = inspectProtocolRequest(api.api, payload, expectedScenarioImage, [
        expectedImageBytes,
        expectedMcpImageBytes,
      ]);
      const priorCalls = observations.filter(
        (item) => item.api === api.api && item.scenario === scenario,
      ).length;
      const callEvidence = {
        api: api.api,
        scenario,
        schemaDeclared: evidence.schemaDeclared,
        schema: evidence.schema,
        imageCount: evidence.imageCount,
        toolImageCount: evidence.toolImageCount,
        matchingExpectedToolImageCount: evidence.matchingExpectedToolImageCount,
        toolImageDigests: evidence.toolImageDigests,
        toolImageDigestMatches: evidence.toolImageDigestMatches,
        toolImageLimitNoticePresent: evidence.toolImageLimitNoticePresent,
      };
      observations.push(callEvidence);

      let call;
      let answer;
      if (
        (scenario.startsWith("media:") ||
          scenario.startsWith("mcp:") ||
          scenario.startsWith("mcp_invalid:") ||
          scenario.startsWith("missing:") ||
          scenario.startsWith("read_failure:")) &&
        priorCalls === 0
      ) {
        callOrdinal += 1;
        call = imageToolCall(
          api.api,
          payload,
          "call_media_" + String(callOrdinal).padStart(8, "0"),
          scenario,
        );
      } else if (scenario.startsWith("invalid:")) {
        answer = '{"answer":"JA_SCHEMA_INVALID"}';
      } else if (scenario.startsWith("media:") && api.api === "openai_chat_completions") {
        answer = '{"answer":"JA_SCHEMA_OK"}';
      } else {
        answer = '{"answer":"JA_SCHEMA_OK"}';
      }

      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(
        providerStream(
          api.api,
          "cap_" + api.id + "_" + String(observations.length).padStart(4, "0"),
          call,
          answer ?? "Ja coding capability fixture",
        ),
      );
    } catch {
      response.writeHead(400, { "content-type": "application/json" });
      response.end('{"error":"fixture_rejected_request"}');
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    endpoint: "http://127.0.0.1:" + address.port + "/v1",
    mcpEndpoint: "http://127.0.0.1:" + address.port + "/mcp",
    observations,
    mcpCalls,
    markers: markerByScenario,
    /** sidecar 退出后关闭唯一 loopback listener，避免留下验收端口。 */
    async close() {
      await new Promise((resolvePromise, rejectPromise) => {
        server.close((error) => (error ? rejectPromise(error) : resolvePromise()));
      });
    },
  };
}

/** 只解析显式验收 JAR，避免 runner 覆盖另一构建进程正在写入的 target。 */
async function resolveJar() {
  const jar = resolve(
    process.env.JA_MEDIA_SCHEMA_JAR ??
      join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0)
    throw new Error("media/schema acceptance JAR is unavailable");
  return jar;
}

/** Native 模式固定使用调用方给定的非空产物，不在失败时回退到工作区 JAR。 */
async function resolveExecutable(executable) {
  const path = resolve(executable);
  const metadata = await stat(path);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("media/schema acceptance Native executable is unavailable");
  }
  return path;
}

/** 解析显式指定的 JDK 并执行 Java 25 门禁，避免 PATH 默认版本被静默采用。 */
async function resolveJava25() {
  const java =
    process.env.JA_TEST_JAVA ??
    (process.env.JAVA_HOME
      ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
      : process.platform === "win32"
        ? "java.exe"
        : "java");
  let output = "";
  try {
    const result = await execFileAsync(java, ["-version"], {
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    output = (result.stdout ?? "") + "\n" + (result.stderr ?? "");
  } catch (error) {
    output = (error?.stdout ?? "") + "\n" + (error?.stderr ?? "");
    if (output.trim() === "") throw new Error("selected Java executable could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25)
    throw new Error("media/schema acceptance requires Java 25");
  return java;
}

/** 在固定 Native 产物与默认 JDK 25/JAR 间选择唯一启动规格，防止静默切换验收对象。 */
async function resolveLaunch(parsed) {
  const environmentExecutable = process.env.JA_MEDIA_SCHEMA_EXECUTABLE;
  if (parsed.executable !== undefined && environmentExecutable !== undefined) {
    throw new Error("specify the Native executable by argument or environment, not both");
  }
  const executable = parsed.executable ?? environmentExecutable;
  if (executable !== undefined) {
    if (process.env.JA_MEDIA_SCHEMA_JAR !== undefined) {
      throw new Error("JA_MEDIA_SCHEMA_JAR and Native executable are mutually exclusive");
    }
    const path = await resolveExecutable(executable);
    return { kind: "native", command: path, prefixArgs: [], artifact: path, javaMajor: null };
  }
  const jar = await resolveJar();
  const java = await resolveJava25();
  return { kind: "jvm", command: java, prefixArgs: ["-jar", jar], artifact: jar, javaMajor: 25 };
}

/** 只把 JA-RPC 稳定错误码带入诊断，不回显请求正文或凭据相关载荷。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined)
    throw new Error(operation + " failed: " + requireRpcErrorCode(frame.error));
  if (frame?.result === undefined) throw new Error(operation + " returned no result");
  return frame.result;
}

/** 固定 loopback 模型能力；可单独关闭一个模型的 Schema 声明以验证 Java 的请求前拒绝。 */
export function multiProviderConfiguration(endpoint, mcpEndpoint, schemaUnsupportedProviderId) {
  const documents = apis.map((entry) =>
    providerConfigurationDocument({
      endpoint,
      name: "Ja media/schema " + entry.id + " loopback",
      api: entry.api,
      model: entry.model,
      selectedProviderId: entry.providerId,
      selectedModelId: entry.modelId,
      selectedCredentialId: entry.credentialId,
      reasoningLevel: "off",
    }),
  );
  const document = structuredClone(documents[0]);
  document.providers = documents.flatMap((providerDocument) => providerDocument.providers);
  if (
    schemaUnsupportedProviderId !== undefined &&
    !document.providers.some((provider) => provider.provider_id === schemaUnsupportedProviderId)
  ) {
    throw new Error("schema unsupported fixture provider is unknown");
  }
  document.default_provider_id = apis[0].providerId;
  document.default_model_id = apis[0].modelId;
  for (const provider of document.providers) {
    provider.models[0].capabilities.supports_output_schema =
      provider.provider_id !== schemaUnsupportedProviderId;
    provider.models[0].capabilities.context_window_tokens = 1_000_000;
    provider.agent_defaults.context.auto_compact = false;
  }
  document.mcp_servers =
    mcpEndpoint === undefined
      ? []
      : [
          {
            mcp_id: "mcp_media_fixture",
            name: "Ja media fixture",
            transport: "streamable_http",
            endpoint: mcpEndpoint,
            args: [],
            env: {},
            headers: {},
            auth: { kind: "none" },
            enabled: true,
          },
        ];
  return document;
}

/** 构造并提交运行级 Schema 请求；保留错误帧以验证受理前拒绝的稳定错误码。 */
async function startTurn(session, threadId, marker, schema = outputSchema()) {
  const params = {
    threadId,
    operationKey: createOperationKey(),
    content: [{ type: "text", text: "Use only the requested capability. Marker: " + marker }],
  };
  if (schema !== undefined && schema !== null) params.runtimeOverrides = { outputSchema: schema };
  return session.request("turn/start", params);
}

/** 通过运行级覆盖提交普通 Turn，让 Schema 断言不污染已保存 Provider 配置。 */
async function runTurn(session, threadId, marker, schema = outputSchema()) {
  const accepted = rpcResult(await startTurn(session, threadId, marker, schema), "turn/start");
  assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.threadId === threadId && frame.params?.turnId === accepted.turnId,
    requestTimeoutMs,
  );
  return { accepted, terminal };
}

/** 在 Thread 授权下分段读取受管图片，校验历史引用确实能还原原始媒体字节。 */
async function readAttachmentPreview(session, threadId, attachmentId) {
  const opened = rpcResult(
    await session.request("attachment/preview/open", {
      attachmentId,
      authorization: { kind: "thread", threadId },
    }),
    "attachment/preview/open",
  );
  assert.equal(opened.previewKind, "image");
  const chunks = [];
  try {
    let offsetBytes = 0;
    while (true) {
      const part = rpcResult(
        await session.request("attachment/preview/read", {
          previewSessionId: opened.previewSessionId,
          offsetBytes,
          limitBytes: 64 * 1024,
        }),
        "attachment/preview/read",
      );
      assert.equal(part.offsetBytes, offsetBytes);
      const bytes = Buffer.from(part.contentBase64, "base64");
      chunks.push(bytes);
      offsetBytes = part.nextOffsetBytes;
      assert.ok(part.eof || bytes.length > 0, "attachment preview read must advance or reach EOF");
      if (part.eof) break;
    }
  } finally {
    await session.request("attachment/preview/close", {
      previewSessionId: opened.previewSessionId,
    });
  }
  return Buffer.concat(chunks);
}

/** 验证资源身份缺失与 blob 无法读取均返回稳定、脱敏的附件错误。 */
async function assertAttachmentReadFailures(
  session,
  threadId,
  missingId,
  unreadableId,
  unreadableBlobPath,
) {
  const missing = await session.request("attachment/preview/open", {
    attachmentId: missingId,
    authorization: { kind: "thread", threadId },
  });
  assert.equal(requireRpcErrorCode(missing.error), "ATTACHMENT_NOT_FOUND");

  await writeFile(unreadableBlobPath, Buffer.alloc(0));
  const opened = rpcResult(
    await session.request("attachment/preview/open", {
      attachmentId: unreadableId,
      authorization: { kind: "thread", threadId },
    }),
    "attachment/preview/open unreadable fixture",
  );
  const unreadable = await session.request("attachment/preview/read", {
    previewSessionId: opened.previewSessionId,
    offsetBytes: 0,
    limitBytes: 64 * 1024,
  });
  assert.equal(requireRpcErrorCode(unreadable.error), "ATTACHMENT_UNAVAILABLE");
  await session.request("attachment/preview/close", { previewSessionId: opened.previewSessionId });
}

/** 按目标 Turn 提取受管媒体；失败时仅报告结构键、状态及媒体元信息。 */
function requireToolMediaReference(snapshot, expected) {
  const items = snapshot?.items ?? [];
  const allToolItems = items.filter((item) => item?.kind === "tool_call");
  const toolItems = allToolItems.filter(
    (item) => expected.turnId === undefined || item.turnId === expected.turnId,
  );
  const references = toolItems.flatMap((item) => item?.presentation?.media ?? []);
  const match = references.find(
    (media) =>
      media.displayName === expected.displayName &&
      media.mediaType === "image/png" &&
      media.sizeBytes === expected.sizeBytes,
  );
  const toolEvidence = allToolItems.slice(-16).map((item) => ({
    itemKeys: Object.keys(item).slice(0, 32),
    turnId: typeof item.turnId === "string" ? item.turnId : null,
    matchesExpectedTurn: expected.turnId === undefined || item.turnId === expected.turnId,
    toolName: typeof item.toolName === "string" ? item.toolName : null,
    itemStatus: typeof item.status === "string" ? item.status : null,
    presentationKeys: Object.keys(item.presentation ?? {}).slice(0, 32),
    status: typeof item.presentation?.status === "string" ? item.presentation.status : null,
    errorCode:
      typeof item.presentation?.errorCode === "string" ? item.presentation.errorCode : null,
    mediaCount: Array.isArray(item.presentation?.media) ? item.presentation.media.length : 0,
    media: (item.presentation?.media ?? []).map((media) => ({
      displayName: media.displayName ?? null,
      mediaType: media.mediaType ?? null,
      sizeBytes: media.sizeBytes ?? null,
    })),
  }));
  assert.ok(
    match,
    "thread/read must retain the imported Tool image reference: " +
      JSON.stringify({
        provider: expected.provider ?? null,
        turnId: expected.turnId ?? null,
        workspace: expected.workspace ?? null,
        requestedPath: expected.requestedPath ?? null,
        sourcePath: expected.sourcePath ?? null,
        matchingTurnToolCalls: toolItems.length,
        recentSnapshotItems: items.slice(-16).map((item) => ({
          kind: typeof item?.kind === "string" ? item.kind : null,
          turnId: typeof item?.turnId === "string" ? item.turnId : null,
          keys: item !== null && typeof item === "object" ? Object.keys(item).slice(0, 24) : [],
        })),
        displayName: expected.displayName,
        sizeBytes: expected.sizeBytes,
        toolCalls: toolEvidence,
      }),
  );
  assert.match(match.attachmentId, /^att_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
  return match;
}

/** 同 launcher/profile 重启后显式重开 workspace，因为其打开态归进程所有。 */
async function restartSession(session, launch, directories, endpoint) {
  await session.shutdown().catch(() => undefined);
  await session.forceClose();
  const restarted = new JsonlSession({
    command: launch.command,
    prefixArgs: launch.prefixArgs,
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  const initialized = rpcResult(
    await restarted.request("runtime/initialize", initializeParams()),
    "runtime/initialize after restart",
  );
  assert.equal(initialized?.runtime?.engine, "ja-kernel");
  restarted.notifyInitialized();
  await restarted.waitForEvent(
    "runtime/status-changed",
    (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
    30_000,
  );
  rpcResult(
    await restarted.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Ja media schema loopback acceptance",
    }),
    "workspace/open after App Server restart",
  );
  return restarted;
}

/** 只解析完成 Turn 的已提交终答，避免把流式中间文本当作 Schema 校验结果。 */
function requireStructuredAnswer(turn, expected) {
  assert.equal(turn.terminal.params?.state, "completed");
  const text = committedTerminalReply(turn.terminal, turn.accepted.turnId);
  assert.ok(text !== null, "completed schema Turn must have a committed answer");
  assert.deepEqual(JSON.parse(text), { answer: expected });
  return text;
}

/** 只保留验证 invalid-schema 归因所需的请求约束与终态正文，并限制终答长度。 */
function invalidSchemaTurnEvidence(api, scenario, observation, turn) {
  const finalText = committedTerminalReply(turn.terminal, turn.accepted.turnId);
  const errorCode = turn.terminal.params?.errorCode;
  return {
    api,
    scenario,
    request: {
      observedScenario: observation?.scenario ?? null,
      schemaDeclared: observation?.schemaDeclared === true,
      schema: observation?.schema ?? null,
    },
    terminal: {
      state: turn.terminal.params?.state ?? null,
      errorCode:
        typeof errorCode === "string" && /^[A-Z][A-Z0-9_]{1,63}$/u.test(errorCode)
          ? errorCode
          : null,
      committedText: typeof finalText === "string" ? finalText.slice(0, 512) : null,
      committedTextTruncated: typeof finalText === "string" && finalText.length > 512,
    },
  };
}

/** 从 Thread 快照提取本媒体 Turn 的 Tool 状态、错误码与媒体元信息，不保留输出正文。 */
function mediaToolHistoryEvidence(snapshot, turnId) {
  return (snapshot?.items ?? [])
    .filter((item) => item?.kind === "tool_call" && item.turnId === turnId)
    .map((item) => ({
      itemKeys: Object.keys(item).slice(0, 32),
      toolName: typeof item.toolName === "string" ? item.toolName : null,
      presentationKeys: Object.keys(item.presentation ?? {}).slice(0, 32),
      itemStatus: typeof item.status === "string" ? item.status : null,
      status: typeof item.presentation?.status === "string" ? item.presentation.status : null,
      errorCode:
        typeof item.presentation?.errorCode === "string" ? item.presentation.errorCode : null,
      mediaCount: Array.isArray(item.presentation?.media) ? item.presentation.media.length : 0,
      media: (item.presentation?.media ?? []).map((media) => ({
        displayName: media.displayName ?? null,
        mediaType: media.mediaType ?? null,
        sizeBytes: media.sizeBytes ?? null,
      })),
    }));
}

/** 只清理 createIsolatedDirectories 创建的唯一临时 profile，保护用户现存数据。 */
async function cleanupProfile(root) {
  const physicalTemp = resolve(await realpath(tmpdir()));
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/** 验证 Schema 能力拒绝与 Tool 图片；固定 Native/JAR 入口贯穿重启，失败诊断仍受 profile 开关控制。 */
export async function runAcceptance(args = []) {
  if (process.platform !== "win32") throw new Error("media/schema acceptance requires Windows");
  const launch = await resolveLaunch(parseLaunchArguments(args));
  const directories = await createIsolatedDirectories();
  const image = createLargePngFixture(0);
  const mcpImageBytes = Buffer.concat([image.bytes, Buffer.from("MCP")]);
  const imageDigest = createHash("sha256").update(image.bytes).digest("hex");
  const mcpImageDigest = createHash("sha256").update(mcpImageBytes).digest("hex");
  const imagePath = join(directories.workspace, "ja-capability-image.png");
  const readFailurePath = join(directories.workspace, "read-failure");
  let fixture;
  let session;
  let runFailure;
  try {
    await writeFile(imagePath, image.bytes);
    const storedImageDigest = createHash("sha256")
      .update(await readFile(imagePath))
      .digest("hex");
    assert.equal(
      storedImageDigest,
      imageDigest,
      "workspace image fixture must contain its generated PNG bytes",
    );
    await mkdir(readFailurePath);
    fixture = await startCapabilityProviderFixture(image.bytes, mcpImageBytes);
    session = new JsonlSession({
      command: launch.command,
      prefixArgs: launch.prefixArgs,
      directories,
      apiKey: placeholderSecret,
      endpoint: fixture.endpoint,
    });

    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );

    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const document = multiProviderConfiguration(
      fixture.endpoint,
      fixture.mcpEndpoint,
      apis[0].providerId,
    );
    document.providers.forEach((provider) => {
      provider.network_timeouts.request_timeout_ms = requestTimeoutMs;
    });
    rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    let credentialVersion = configuration.cas.credentialVersion;
    for (const entry of apis) {
      const credential = rpcResult(
        await session.request("credential/set", {
          credentialId: entry.credentialId,
          secret: placeholderSecret,
          expectedVersion: credentialVersion,
        }),
        "credential/set",
      );
      assert.equal(credential.credentialId, entry.credentialId);
      assert.equal(credential.configured, true);
      credentialVersion = credential.version;
    }
    const workspace = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja media schema loopback acceptance",
      }),
      "workspace/open",
    );

    const results = {};
    const mediaThreads = [];
    const unsupportedThread = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Unsupported output schema capability",
        providerId: apis[0].providerId,
        modelId: apis[0].modelId,
        reasoningLevel: "off",
        accessMode: "full_access",
        collaborationMode: "default",
      }),
      "thread/create unsupported schema capability",
    );
    assert.equal(unsupportedThread.workspaceId, workspace.workspaceId);
    const unsupportedConfiguration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read with unsupported schema capability",
    );
    const unsupportedProvider = unsupportedConfiguration.user?.document?.providers?.find(
      (provider) => provider.provider_id === apis[0].providerId,
    );
    assert.equal(
      unsupportedProvider?.models?.[0]?.capabilities?.supports_output_schema,
      false,
      "Java configuration must own the explicit unsupported Schema capability",
    );
    const beforeUnsupportedSchema = rpcResult(
      await session.request("thread/read", {
        threadId: unsupportedThread.threadId,
        tail: true,
        limit: 200,
      }),
      "thread/read before unsupported Schema request",
    );
    const requestsBeforeUnsupportedSchema = fixture.observations.length;
    const unsupportedSchemaStart = await startTurn(
      session,
      unsupportedThread.threadId,
      fixture.markers.get("unsupported_schema:responses"),
    );
    assert.ok(
      unsupportedSchemaStart.error !== undefined,
      "unsupported Schema capability must be rejected before Turn acceptance",
    );
    assert.equal(
      unsupportedSchemaStart.result,
      undefined,
      "a rejected turn/start must not return an accepted Turn identity",
    );
    const unsupportedSchemaErrorCode = requireRpcErrorCode(unsupportedSchemaStart.error);
    const afterUnsupportedSchema = rpcResult(
      await session.request("thread/read", {
        threadId: unsupportedThread.threadId,
        tail: true,
        limit: 200,
      }),
      "thread/read after unsupported Schema request",
    );
    const priorTurnIds = new Set(beforeUnsupportedSchema.turns.map((turn) => turn.turnId));
    const unsupportedSchemaEvidence = {
      providerId: apis[0].providerId,
      modelId: apis[0].modelId,
      configuredSupport: unsupportedProvider.models[0].capabilities.supports_output_schema,
      accepted: unsupportedSchemaStart.result !== undefined,
      errorCode: unsupportedSchemaErrorCode,
      rpcCode: unsupportedSchemaStart.error.code,
      revisionBefore: beforeUnsupportedSchema.revision,
      revisionAfter: afterUnsupportedSchema.revision,
      itemsBefore: beforeUnsupportedSchema.items.length,
      itemsAfter: afterUnsupportedSchema.items.length,
      turnsBefore: beforeUnsupportedSchema.turns.length,
      turnsAfter: afterUnsupportedSchema.turns.length,
      successfulTerminal: afterUnsupportedSchema.turns.some(
        (turn) => !priorTurnIds.has(turn.turnId) && turn.status === "completed",
      ),
      providerDispatches: fixture.observations.length - requestsBeforeUnsupportedSchema,
    };
    assert.equal(
      unsupportedSchemaEvidence.errorCode,
      "OUTPUT_SCHEMA_UNSUPPORTED",
      "unsupported Schema capability must expose the stable error code",
    );
    assert.equal(unsupportedSchemaEvidence.rpcCode, -32098);
    assert.equal(unsupportedSchemaEvidence.revisionAfter, unsupportedSchemaEvidence.revisionBefore);
    assert.equal(unsupportedSchemaEvidence.itemsAfter, unsupportedSchemaEvidence.itemsBefore);
    assert.equal(unsupportedSchemaEvidence.turnsAfter, unsupportedSchemaEvidence.turnsBefore);
    assert.equal(unsupportedSchemaEvidence.successfulTerminal, false);
    assert.equal(unsupportedSchemaEvidence.providerDispatches, 0);

    const enabledDocument = structuredClone(unsupportedConfiguration.user.document);
    const enabledProvider = enabledDocument.providers.find(
      (provider) => provider.provider_id === apis[0].providerId,
    );
    enabledProvider.models[0].capabilities.supports_output_schema = true;
    rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: unsupportedConfiguration.cas.userVersion,
        document: enabledDocument,
      }),
      "configuration/replace after unsupported schema capability check",
    );
    const enabledConfiguration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after schema capability restore",
    );
    assert.equal(
      enabledConfiguration.user?.document?.providers?.find(
        (provider) => provider.provider_id === apis[0].providerId,
      )?.models?.[0]?.capabilities?.supports_output_schema,
      true,
      "Java configuration must restore the Schema-capable model for the remaining matrix",
    );
    results.outputSchemaUnsupported = unsupportedSchemaEvidence;

    for (const entry of apis) {
      const thread = rpcResult(
        await session.request("thread/create", {
          cwd: directories.workspace,
          title: "Media and schema " + entry.id,
          providerId: entry.providerId,
          modelId: entry.modelId,
          reasoningLevel: "off",
          accessMode: "full_access",
          collaborationMode: "default",
        }),
        "thread/create " + entry.id,
      );
      assert.equal(thread.workspaceId, workspace.workspaceId);
      const validMarker = fixture.markers.get("valid:" + entry.id);
      const validTurn = await runTurn(session, thread.threadId, validMarker);
      requireStructuredAnswer(validTurn, "JA_SCHEMA_OK");

      const invalidSchemaMarker = fixture.markers.get("invalid_schema:" + entry.id);
      const invalidSchemaTurn = await runTurn(session, thread.threadId, invalidSchemaMarker, {
        type: "string",
      });
      assert.equal(
        invalidSchemaTurn.terminal.params?.state,
        "failed",
        entry.api + " must reject an invalid output schema before Provider dispatch",
      );
      assert.equal(
        committedTerminalReply(invalidSchemaTurn.terminal, invalidSchemaTurn.accepted.turnId),
        null,
      );
      assert.equal(
        fixture.observations.filter(
          (item) => item.api === entry.api && item.scenario === "invalid_schema:" + entry.id,
        ).length,
        0,
        entry.api + " must not dispatch the malformed Schema Turn",
      );

      const invalidMarker = fixture.markers.get("invalid:" + entry.id);
      const invalidTurn = await runTurn(session, thread.threadId, invalidMarker);
      const invalidObservation = [...fixture.observations]
        .reverse()
        .find((item) => item.api === entry.api && item.scenario === "invalid:" + entry.id);
      const invalidEvidence = invalidSchemaTurnEvidence(
        entry.api,
        "invalid:" + entry.id,
        invalidObservation,
        invalidTurn,
      );
      if (entry.api === "openai_responses" && process.env.JA_MEDIA_SCHEMA_EVIDENCE_PATH) {
        await writeFile(
          process.env.JA_MEDIA_SCHEMA_EVIDENCE_PATH,
          JSON.stringify(invalidEvidence, null, 2) + "\n",
          "utf8",
        );
      }
      assert.equal(
        invalidTurn.terminal.params?.state,
        "failed",
        entry.api +
          " must reject a valid JSON value outside the requested schema: " +
          JSON.stringify(invalidEvidence),
      );
      assert.equal(committedTerminalReply(invalidTurn.terminal, invalidTurn.accepted.turnId), null);

      const mediaTurn = await runTurn(
        session,
        thread.threadId,
        fixture.markers.get("media:" + entry.id),
      );
      requireStructuredAnswer(mediaTurn, "JA_SCHEMA_OK");
      const mediaHistory = rpcResult(
        await session.request("thread/read", {
          threadId: thread.threadId,
          tail: true,
          limit: 200,
        }),
        "thread/read after workspace Tool image " + entry.id,
      );
      const workspaceReference = requireToolMediaReference(mediaHistory, {
        provider: entry.id,
        turnId: mediaTurn.accepted.turnId,
        workspace: directories.workspace,
        requestedPath: basename(imagePath),
        sourcePath: imagePath,
        displayName: basename(imagePath),
        sizeBytes: image.bytes.length,
      });
      const workspaceObservation = fixture.observations.filter(
        (item) => item.api === entry.api && item.scenario === "media:" + entry.id,
      );
      assert.ok(
        workspaceObservation.length >= 1,
        entry.api + " must receive the workspace Tool call",
      );
      if (entry.api === "openai_chat_completions") {
        assert.equal(
          workspaceObservation.every((item) => item.imageCount === 0),
          true,
          "Chat Completions must omit unsupported workspace Tool images",
        );
        assert.equal(
          workspaceObservation.every((item) => item.toolImageCount === 0),
          true,
        );
        assert.ok(
          workspaceObservation.some((item) => item.toolImageLimitNoticePresent),
          "Chat Provider request must explain that image pixels were not sent",
        );
      } else {
        const roundTrip = workspaceObservation.find(
          (item) => item.matchingExpectedToolImageCount >= 1 && item.toolImageDigestMatches,
        );
        assert.ok(roundTrip, entry.api + " must send the workspace Tool image to the Provider");
        assert.equal(roundTrip.schemaDeclared, true);
        assert.equal(roundTrip.toolImageDigestMatches, true);
      }
      mediaThreads.push({
        api: entry.id,
        turnId: mediaTurn.accepted.turnId,
        threadId: thread.threadId,
        mediaReference: workspaceReference,
        source: "workspace",
      });

      const mcpTurn = await runTurn(
        session,
        thread.threadId,
        fixture.markers.get("mcp:" + entry.id),
      );
      requireStructuredAnswer(mcpTurn, "JA_SCHEMA_OK");
      const mcpHistory = rpcResult(
        await session.request("thread/read", {
          threadId: thread.threadId,
          tail: true,
          limit: 200,
        }),
        "thread/read after MCP Tool image " + entry.id,
      );
      const mcpReference = requireToolMediaReference(mcpHistory, {
        provider: entry.id,
        turnId: mcpTurn.accepted.turnId,
        displayName: "mcp-image-1.png",
        sizeBytes: mcpImageBytes.length,
      });
      const mcpObservation = fixture.observations.filter(
        (item) => item.api === entry.api && item.scenario === "mcp:" + entry.id,
      );
      assert.ok(mcpObservation.length >= 1, entry.api + " must receive the MCP Tool call");
      if (entry.api === "openai_chat_completions") {
        assert.equal(
          mcpObservation.every((item) => item.imageCount === 0),
          true,
          "Chat Completions must omit unsupported MCP Tool images",
        );
        assert.ok(
          mcpObservation.some((item) => item.toolImageLimitNoticePresent),
          "Chat Provider request must report its MCP Tool-image limitation",
        );
      } else {
        const roundTrip = mcpObservation.find(
          (item) => item.matchingExpectedToolImageCount >= 1 && item.toolImageDigestMatches,
        );
        assert.ok(roundTrip, entry.api + " must send typed MCP image bytes to the Provider");
        assert.equal(roundTrip.toolImageDigestMatches, true);
      }
      mediaThreads.push({
        api: entry.id,
        turnId: mcpTurn.accepted.turnId,
        threadId: thread.threadId,
        mediaReference: mcpReference,
        source: "mcp",
      });

      for (const failureScenario of ["missing", "read_failure", "mcp_invalid"]) {
        const failureTurn = await runTurn(
          session,
          thread.threadId,
          fixture.markers.get(failureScenario + ":" + entry.id),
        );
        requireStructuredAnswer(failureTurn, "JA_SCHEMA_OK");
        const failureHistory = rpcResult(
          await session.request("thread/read", {
            threadId: thread.threadId,
            tail: true,
            limit: 200,
          }),
          "thread/read after media failure " + failureScenario + " " + entry.id,
        );
        const failedTools = mediaToolHistoryEvidence(failureHistory, failureTurn.accepted.turnId);
        assert.ok(
          failedTools.some((item) => item.status === "error"),
          entry.api +
            " must persist the " +
            failureScenario +
            " Tool error: " +
            JSON.stringify(failedTools),
        );
        assert.ok(
          failedTools.every((item) => item.mediaCount === 0),
          entry.api +
            " must not attach media after " +
            failureScenario +
            ": " +
            JSON.stringify(failedTools),
        );
      }
      results[entry.id] = {
        schemaValid: "completed",
        schemaInvalidRequest: invalidSchemaTurn.terminal.params?.state,
        schemaInvalid: invalidTurn.terminal.params?.state,
        media: mediaTurn.terminal.params?.state,
        mcpMedia: mcpTurn.terminal.params?.state,
      };
    }

    for (const entry of apis) {
      for (const scenario of [
        "valid:" + entry.id,
        "invalid:" + entry.id,
        "media:" + entry.id,
        "mcp:" + entry.id,
      ]) {
        const first = fixture.observations.find(
          (item) => item.api === entry.api && item.scenario === scenario,
        );
        assert.ok(first, entry.api + " did not reach the loopback fixture for " + scenario);
        assert.equal(
          first.schemaDeclared,
          true,
          entry.api + " omitted output schema from its request",
        );
      }
    }
    session = await restartSession(session, launch, directories, fixture.endpoint);
    for (const entry of mediaThreads) {
      const history = rpcResult(
        await session.request("thread/read", {
          threadId: entry.threadId,
          tail: true,
          limit: 200,
        }),
        "thread/read after App Server restart",
      );
      const persisted = requireToolMediaReference(history, {
        provider: entry.api,
        turnId: entry.turnId,
        displayName: entry.source === "workspace" ? basename(imagePath) : "mcp-image-1.png",
        sizeBytes: entry.source === "workspace" ? image.bytes.length : mcpImageBytes.length,
      });
      assert.equal(
        persisted.attachmentId,
        entry.mediaReference.attachmentId,
        "restart history must preserve the Tool attachment identity",
      );
      const expectedBytes = entry.source === "workspace" ? image.bytes : mcpImageBytes;
      assert.deepEqual(
        await readAttachmentPreview(session, entry.threadId, persisted.attachmentId),
        expectedBytes,
        "restart history attachment must return the exact imported image bytes",
      );
    }

    const missingBlobPath = join(directories.data, "attachments", "blobs", imageDigest);
    const mcpBlobPath = join(directories.data, "attachments", "blobs", mcpImageDigest);
    const responsesThread = mediaThreads.find((entry) => entry.source === "workspace").threadId;
    const mcpThread = mediaThreads.find((entry) => entry.source === "mcp").threadId;
    const unknownAttachment = "att_missing_media_fixture";
    const knownMcpAttachment = mediaThreads.find((entry) => entry.source === "mcp").mediaReference
      .attachmentId;
    const missingBlobBytes = await readFile(missingBlobPath);
    const requestsBeforeMissingResource = fixture.observations.length;
    await assertAttachmentReadFailures(
      session,
      responsesThread,
      unknownAttachment,
      knownMcpAttachment,
      mcpBlobPath,
    );
    await writeFile(mcpBlobPath, mcpImageBytes);
    await rm(missingBlobPath);
    const missingResourceTurn = await runTurn(
      session,
      responsesThread,
      "JA_CAP_MANAGED_IMAGE_RESOURCE_MISSING",
    );
    assert.equal(
      missingResourceTurn.terminal.params?.state,
      "failed",
      "missing stored media must stop model request preparation",
    );
    assert.equal(
      fixture.observations.length,
      requestsBeforeMissingResource,
      "missing stored media must fail before a Provider request is sent",
    );
    await writeFile(missingBlobPath, missingBlobBytes);
    await writeFile(mcpBlobPath, Buffer.alloc(0));
    const requestsBeforeReadFailure = fixture.observations.length;
    const unreadableResourceTurn = await runTurn(
      session,
      mcpThread,
      "JA_CAP_MANAGED_IMAGE_READ_FAILURE",
    );
    assert.equal(
      unreadableResourceTurn.terminal.params?.state,
      "failed",
      "unreadable stored media must stop model request preparation",
    );
    assert.equal(
      fixture.observations.length,
      requestsBeforeReadFailure,
      "unreadable stored media must fail before a Provider request is sent",
    );
    await writeFile(mcpBlobPath, mcpImageBytes);

    const report = {
      status: "passed",
      javaMajor: launch.javaMajor,
      jar: launch.kind === "jvm" ? basename(launch.artifact) : null,
      runtime: {
        launcher: launch.kind,
        javaMajor: launch.javaMajor,
        artifact: basename(launch.artifact),
      },
      protocols: results,
      toolImage: {
        workspaceFile: basename(imagePath),
        mediaType: "image/png",
        sizeBytes: image.bytes.length,
        sha256: imageDigest,
        providerRoundTrips: fixture.observations.filter(
          (item) =>
            item.scenario.startsWith("media:") &&
            item.matchingExpectedToolImageCount >= 1 &&
            item.toolImageDigestMatches,
        ).length,
        historyReferences: mediaThreads.length,
        restartReferences: mediaThreads.length,
      },
      mcpImage: {
        serverId: "mcp_media_fixture",
        toolName: "read_fixture_image",
        mediaType: "image/png",
        sizeBytes: mcpImageBytes.length,
        sha256: mcpImageDigest,
        calls: fixture.mcpCalls.length,
        successfulProviderRoundTrips: fixture.observations.filter(
          (item) =>
            item.scenario.startsWith("mcp:") &&
            item.matchingExpectedToolImageCount >= 1 &&
            item.toolImageDigestMatches,
        ).length,
      },
      attachmentFailures: {
        missingIdentity: "ATTACHMENT_NOT_FOUND",
        missingBlob: "failed before Provider dispatch",
        unreadableBlob: "ATTACHMENT_UNAVAILABLE and failed before Provider dispatch",
      },
      externalProviderCalls: 0,
    };
    process.stdout.write("MEDIA_SCHEMA_LOOPBACK_PASS " + JSON.stringify(report) + "\n");
    return report;
  } catch (error) {
    runFailure = error;
    throw error;
  } finally {
    try {
      try {
        if (session !== undefined) {
          await session.shutdown().catch(() => undefined);
          await session.forceClose();
        }
      } finally {
        await fixture?.close();
      }
    } finally {
      if (runFailure !== undefined && process.env.JA_MEDIA_SCHEMA_KEEP_FAILURE_PROFILE === "1") {
        try {
          await writeFile(
            join(directories.logs, "app-server.stderr.redacted.log"),
            session?.stderr ?? "",
            "utf8",
          );
          process.stderr.write("MEDIA_SCHEMA_FAILURE_PROFILE " + directories.root + "\n");
        } catch (preserveError) {
          process.stderr.write(
            "MEDIA_SCHEMA_FAILURE_PROFILE_WRITE_FAILED " +
              String(preserveError?.message ?? preserveError).slice(0, 512) +
              "\n",
          );
        }
      } else {
        await cleanupProfile(directories.root);
      }
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  runAcceptance(process.argv.slice(2)).catch((error) => {
    process.stderr.write(
      "media/schema loopback failed: " + String(error?.message ?? error).slice(0, 2_000) + "\n",
    );
    process.exitCode = 1;
  });
}
