// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

const INVALID_GREP_CALL_ID = "call_tool_catalog_grep_invalid";
const VALID_GREP_CALL_ID = "call_tool_catalog_grep_valid";
const FIND_CALL_ID = "call_tool_catalog_find";
const LS_CALL_ID = "call_tool_catalog_ls";
const COMMENTARY_INVALID = "JA_TOOL_CATALOG_COMMENTARY_INVALID";
const COMMENTARY_VALID = "JA_TOOL_CATALOG_COMMENTARY_VALID";
const COMMENTARY_FIND = "JA_TOOL_CATALOG_COMMENTARY_FIND";
const COMMENTARY_LS = "JA_TOOL_CATALOG_COMMENTARY_LS";
const FINAL_MARKER = "JA_TOOL_CATALOG_FINAL_OK";
const GREP_MARKER = "JA_TOOL_CATALOG_NEEDLE";

/** 将一个 Responses SSE 事件固定为严格 JSONL-compatible frame，避免 fixture 依赖宽松解析。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequenceNumber,
    ...payload,
  })}\n\n`;
}

/** 构造 Provider 终态 envelope，补齐真实 JVM adapter 所需的最小响应字段。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "tool-catalog-e2e",
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

/** 从 OpenAI Responses Tool 定义提取名称，fixture 只保存名称而不落盘 Schema 正文。 */
function toolName(definition) {
  if (typeof definition?.name === "string") return definition.name;
  return undefined;
}

/** 读取 Responses function 的参数 Schema，fixture 严格绑定本轮发布的原生 function 形状。 */
function toolParameters(definition) {
  return definition?.parameters ?? {};
}

/** 找到当前请求的工具 Schema，避免 fixture 预先复制生产端字段命名。 */
function parametersFor(payload, name) {
  const definition = (Array.isArray(payload?.tools) ? payload.tools : []).find(
    (candidate) => toolName(candidate) === name,
  );
  return toolParameters(definition);
}

const SCHEMA_TOOL_NAMES = Object.freeze(["grep", "find", "ls"]);
const SCHEMA_FIELD_DIFFERENCES = Object.freeze([
  "tool",
  "strict",
  "root_type",
  "properties",
  "required",
  "additional_properties",
  "non_nullable",
  "nullable",
]);
const SCHEMA_TYPES = Object.freeze({
  grep: Object.freeze({ query: "string", filePattern: "string", path: "string", maxResults: "integer" }),
  find: Object.freeze({ pattern: "string", path: "string", maxResults: "integer" }),
  ls: Object.freeze({ path: "string", maxEntries: "integer" }),
});
const TOOL_SCHEMA_CONTRACT = Object.freeze({
  grep: Object.freeze({
    properties: Object.freeze(["filePattern", "maxResults", "path", "query"]),
    required: Object.freeze(["filePattern", "maxResults", "path", "query"]),
    nonNullable: Object.freeze(["query"]),
    nullable: Object.freeze(["filePattern", "maxResults", "path"]),
  }),
  find: Object.freeze({
    properties: Object.freeze(["maxResults", "path", "pattern"]),
    required: Object.freeze(["maxResults", "path", "pattern"]),
    nonNullable: Object.freeze(["pattern"]),
    nullable: Object.freeze(["maxResults", "path"]),
  }),
  ls: Object.freeze({
    properties: Object.freeze(["maxEntries", "path"]),
    required: Object.freeze(["maxEntries", "path"]),
    nonNullable: Object.freeze([]),
    nullable: Object.freeze(["maxEntries", "path"]),
  }),
});

/** 生成只含预定义工具和字段类别的 schema 契约错误，禁止把响应中的未知值带入诊断。 */
function schemaContractFailure(toolName, fieldDifference) {
  const error = new Error("tool catalog schema contract mismatch");
  error.code = "SCHEMA_CONTRACT";
  error.toolName = SCHEMA_TOOL_NAMES.includes(toolName) ? toolName : "unknown";
  error.fieldDifference = SCHEMA_FIELD_DIFFERENCES.includes(fieldDifference)
    ? fieldDifference
    : "tool";
  return error;
}

/** 对排序后的字符串字段集做严格比较，区分 required 顺序漂移和字段集合漂移。 */
function sameSortedFields(actual, expected) {
  return (
    Array.isArray(actual) &&
    actual.every((value) => typeof value === "string") &&
    JSON.stringify([...actual].sort()) === JSON.stringify([...expected].sort())
  );
}

/** 断言真实 OpenAI strict wire Schema，不接受内部可选 required 或非 nullable 改写缺失。 */
function assertToolSchema(payload, name) {
  const expected = TOOL_SCHEMA_CONTRACT[name];
  if (expected === undefined) throw schemaContractFailure(name, "tool");
  const definition = (Array.isArray(payload?.tools) ? payload.tools : []).find(
    (candidate) => toolName(candidate) === name,
  );
  if (definition === undefined) throw schemaContractFailure(name, "tool");
  if (definition.strict !== true) throw schemaContractFailure(name, "strict");
  const schema = parametersFor(payload, name);
  if (schema?.type !== "object") throw schemaContractFailure(name, "root_type");
  const actualProperties = Object.keys(schema?.properties ?? {});
  if (!sameSortedFields(actualProperties, expected.properties)) {
    throw schemaContractFailure(name, "properties");
  }
  if (!sameSortedFields(schema?.required, expected.properties)) {
    throw schemaContractFailure(name, "required");
  }
  if (schema?.additionalProperties !== false) {
    throw schemaContractFailure(name, "additional_properties");
  }
  const types = SCHEMA_TYPES[name];
  for (const field of expected.nonNullable) {
    if (schema?.properties?.[field]?.type !== types[field]) {
      throw schemaContractFailure(name, "non_nullable");
    }
  }
  for (const field of expected.nullable) {
    if (
      JSON.stringify(schema?.properties?.[field]?.type) !==
      JSON.stringify([types[field], "null"])
    ) {
      throw schemaContractFailure(name, "nullable");
    }
  }
  return schema;
}

/** 将 fixture 异常压缩为固定类别、工具名和字段差异，禁止请求值进入 HTTP 或诊断响应。 */
function safeFixtureFailure(error) {
  if (error?.code === "SCHEMA_CONTRACT") {
    return {
      errorCategory: "schema_contract",
      toolName: SCHEMA_TOOL_NAMES.includes(error.toolName) ? error.toolName : "unknown",
      fieldDifference: SCHEMA_FIELD_DIFFERENCES.includes(error.fieldDifference)
        ? error.fieldDifference
        : "tool",
    };
  }
  return { errorCategory: "fixture", toolName: "unknown", fieldDifference: "tool" };
}

/** 使用本轮确定的 grep/find/ls 字段生成唯一参数，不保留旧字段别名或兼容回退。 */
function toolArguments(payload, name, validGrep) {
  assertToolSchema(payload, name);
  switch (name) {
    case "grep":
      return {
        query: validGrep ? GREP_MARKER : "",
        filePattern: "*.txt",
        path: ".",
        maxResults: 50,
      };
    case "find":
      return { pattern: "*.txt", path: ".", maxResults: 200 };
    case "ls":
      return { path: ".", maxEntries: 200 };
    default:
      throw new Error(`unsupported tool catalog fixture tool: ${name}`);
  }
}

/** 从 Responses continuation 中取出指定 call 的 function_call_output，不暴露其它请求正文。 */
function continuationOutput(payload, callId) {
  const items = Array.isArray(payload?.input) ? payload.input : [];
  const output = items.find(
    (candidate) => candidate?.type === "function_call_output" && candidate?.call_id === callId,
  );
  assert.ok(output !== undefined, `${callId} must have a function_call_output continuation`);
  assert.equal(typeof output.output, "string", `${callId} output must be text`);
  return output.output;
}

/** 校验 schema 失败回传的字段和纠正建议；错误码由 ToolResult 独立字段承载，不绑定正文格式。 */
function assertInvalidGrepCorrection(payload) {
  const items = Array.isArray(payload?.input) ? payload.input : [];
  const call = items.find(
    (candidate) => candidate?.type === "function_call" && candidate?.call_id === INVALID_GREP_CALL_ID,
  );
  assert.ok(call !== undefined, `${INVALID_GREP_CALL_ID} must have a function_call continuation`);
  assert.equal(call.name, "grep");
  const argumentsValue = JSON.parse(call.arguments);
  assert.equal(argumentsValue.query, "");
  assert.equal(argumentsValue.filePattern, "*.txt");
  const output = continuationOutput(payload, INVALID_GREP_CALL_ID);
  assert.match(output, /query/iu);
  assert.match(output, /(minLength|non-empty)/iu);
  assert.match(output, /(correct.*argument|retry)/iu);
}

/** 生成一个 function_call 输出，并用普通公开文本保留动作顺序；不注入私有诊断。 */
function toolStream(responseId, itemId, callId, name, argumentsValue, text) {
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
  return [
    frame("response.created", {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
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
  ].join("");
}

/** 生成无 Tool 的最终答复，确保 Tool continuation 收口后仍有独立终态正文。 */
function finalStream() {
  const responseId = "resp_tool_catalog_final";
  const messageId = "message_tool_catalog_final";
  const text = `基础工具验收完成，结果标记为 ${FINAL_MARKER}。`;
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

/** 自动标题请求使用普通文本结束，避免标题请求被误计入 Tool continuation 阶段。 */
function titleStream() {
  const responseId = "resp_tool_catalog_title";
  const messageId = "message_tool_catalog_title";
  const text = "内置工具目录验收";
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

/** 有界读取请求体，只识别 continuation 和 Tool 目录，不保存用户正文、凭据或完整 prompt。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("tool catalog request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 分帧输出并保留有限阶段标记，让 runner 能把 Provider 顺序与真实 UI 断言关联。 */
async function writeDelayedStream(response, stream, stages, stage) {
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": "text/event-stream; charset=utf-8",
    connection: "close",
  });
  for (const frame of stream.split("\n\n").filter((candidate) => candidate.length > 0)) {
    response.write(`${frame}\n\n`);
    if (frame.includes("output_text.delta")) stages.push(`text_${stage}`);
    if (frame.includes("output_item.added")) stages.push(`tool_${stage}`);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 60));
  }
  response.end();
}

/** 启动只监听 IPv4 loopback 的隔离 Provider，按真实 function_call_output 推进 Tool 生命周期。 */
export async function startToolCatalogFixture() {
  const attempts = [];
  const stages = [];
  let lastFailure = null;
  let releaseInvalidCorrection;
  const invalidCorrectionGate = new Promise((resolvePromise) => {
    releaseInvalidCorrection = resolvePromise;
  });
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const serializedInput = JSON.stringify(payload?.input ?? []);
      const isTitle =
        serializedInput.includes("<user_request>") && serializedInput.includes("<assistant_reply>");
      if (isTitle) {
        attempts.push({ kind: "title" });
        await writeDelayedStream(response, titleStream(), stages, "title");
        return;
      }
      const toolNames = (Array.isArray(payload?.tools) ? payload.tools : [])
        .map(toolName)
        .filter((name) => name !== undefined);
      const hasInvalid = serializedInput.includes(INVALID_GREP_CALL_ID);
      const hasValid = serializedInput.includes(VALID_GREP_CALL_ID);
      const hasFind = serializedInput.includes(FIND_CALL_ID);
      const hasLs = serializedInput.includes(LS_CALL_ID);
      const step = hasLs ? 4 : hasFind ? 3 : hasValid ? 2 : hasInvalid ? 1 : 0;
      attempts.push({ kind: "turn", step, toolNames });
      if (step === 0) {
        await writeDelayedStream(
          response,
          toolStream(
            "resp_tool_catalog_grep_invalid",
            "item_tool_catalog_grep_invalid",
            INVALID_GREP_CALL_ID,
            "grep",
            toolArguments(payload, "grep", false),
            `先验证 grep 参数。${COMMENTARY_INVALID}`,
          ),
          stages,
          "grep_invalid",
        );
        return;
      }
      if (step === 1) {
        assertInvalidGrepCorrection(payload);
        await invalidCorrectionGate;
        await writeDelayedStream(
          response,
          toolStream(
            "resp_tool_catalog_grep_valid",
            "item_tool_catalog_grep_valid",
            VALID_GREP_CALL_ID,
            "grep",
            toolArguments(payload, "grep", true),
            `grep 参数已按错误提示修正。${COMMENTARY_VALID}`,
          ),
          stages,
          "grep_valid",
        );
        return;
      }
      if (step === 2) {
        await writeDelayedStream(
          response,
          toolStream(
            "resp_tool_catalog_find",
            "item_tool_catalog_find",
            FIND_CALL_ID,
            "find",
            toolArguments(payload, "find", true),
            `grep 成功，继续只列出匹配路径。${COMMENTARY_FIND}`,
          ),
          stages,
          "find",
        );
        return;
      }
      if (step === 3) {
        await writeDelayedStream(
          response,
          toolStream(
            "resp_tool_catalog_ls",
            "item_tool_catalog_ls",
            LS_CALL_ID,
            "ls",
            toolArguments(payload, "ls", true),
            `find 未读取文件正文，继续查看当前目录。${COMMENTARY_LS}`,
          ),
          stages,
          "ls",
        );
        return;
      }
      if (step === 4) {
        await writeDelayedStream(response, finalStream(), stages, "final");
        return;
      }
      throw new Error("unexpected tool catalog continuation");
    } catch (error) {
      lastFailure = safeFixtureFailure(error);
      response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: lastFailure.errorCategory }));
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
    /** 在 runner 已检查空 query 失败且失败行展开后才释放纠正请求。 */
    releaseInvalidCorrection() {
      releaseInvalidCorrection();
    },
    /** 只返回有限工具名称/阶段及固定失败类别，不包含 Provider 请求正文或工作区路径。 */
    snapshot() {
      return {
        attempts: attempts.map((attempt) => ({
          ...attempt,
          toolNames: attempt.toolNames === undefined ? undefined : [...attempt.toolNames],
        })),
        stages: [...stages],
        failure: lastFailure === null ? null : { ...lastFailure },
      };
    },
    /** 关闭唯一 loopback listener，保证 fixture 生命周期不越过 runner cleanup。 */
    async close() {
      releaseInvalidCorrection();
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

export const toolCatalogFixtureMarkers = Object.freeze({
  commentaryInvalid: COMMENTARY_INVALID,
  commentaryValid: COMMENTARY_VALID,
  commentaryFind: COMMENTARY_FIND,
  commentaryLs: COMMENTARY_LS,
  final: FINAL_MARKER,
  grep: GREP_MARKER,
  invalidGrepCallId: INVALID_GREP_CALL_ID,
  validGrepCallId: VALID_GREP_CALL_ID,
  findCallId: FIND_CALL_ID,
  lsCallId: LS_CALL_ID,
});
