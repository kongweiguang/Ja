// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import { createServer } from "node:http";

export const RECOVERY_MAX_ATTEMPTS = 6;
const RECOVERY_TOOL_CALL_ID = "call_recovery_once";

/** 构造原生 Responses SSE，真实 Java adapter 必须解析 usage 与最终正文。 */
export function responseStream(text, id = "recovery") {
  const message = {
    id: `msg_${id}`,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  const response = {
    id: `resp_${id}`,
    object: "response",
    created_at: 0,
    model: "review-e2e",
    status: "completed",
    output: [message],
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    usage: {
      input_tokens: 300,
      output_tokens: 12,
      total_tokens: 312,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 },
    },
  };
  return [
    [
      "response.created",
      { response: { ...response, status: "in_progress", output: [], usage: undefined } },
    ],
    [
      "response.output_text.delta",
      { delta: text, item_id: message.id, output_index: 0, content_index: 0, logprobs: [] },
    ],
    ["response.output_text.done", { text, item_id: message.id, output_index: 0, content_index: 0 }],
    ["response.completed", { response }],
  ]
    .map(
      ([type, payload], sequence_number) =>
        `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number, ...payload })}\n\n`,
    )
    .join("");
}

/**
 * 只提取 Provider 原生 input 中最后一条 user message 的文本，避免历史回答中的恢复 marker
 * 把当前请求误分到旧故障序列；fixture 的字符串请求仍保留最小 HTTP 测试入口。
 */
function latestUserText(input) {
  if (!Array.isArray(input)) return typeof input === "string" ? input : undefined;
  for (let index = input.length - 1; index >= 0; index -= 1) {
    const message = input[index];
    if (message === null || typeof message !== "object" || message.role !== "user") continue;
    if (typeof message.content === "string") return message.content;
    if (!Array.isArray(message.content)) continue;
    return message.content
      .filter((block) => block !== null && typeof block === "object" && typeof block.text === "string")
      .map((block) => block.text)
      .join("\n");
  }
  return undefined;
}

/** HTTP fixture 按最后一个用户请求分类，所以无气泡的 continue 仍绑定原问题 marker。 */
export function requestStep(payload) {
  if (String(payload.instructions ?? "").includes("context compaction model")) return "summary";
  const input = JSON.stringify(payload.input ?? []);
  if (input.includes("<user_request>") && input.includes("<assistant_reply>")) return "title";
  const currentUserText = latestUserText(payload.input);
  if (currentUserText?.trim() === "继续") return "continue";
  const markerText =
    currentUserText ??
    (typeof payload.input === "string" ? payload.input : JSON.stringify(payload.input ?? []));
  const marker = [...markerText.matchAll(/JA_RECOVERY_(TURN_\d+|[A-Z][A-Z0-9_]*)/gu)].at(-1)?.[0];
  if (marker === undefined) return "other";
  return marker.startsWith("JA_RECOVERY_TURN_") ? marker.slice("JA_RECOVERY_TURN_".length) : marker;
}

/**
 * 汇总续答请求中不含正文的结构事实，便于真窗断言原问题只出现一次且旧 Tool 不被重新提交。
 */
function continuationContext(payload, step) {
  const input = payload.input;
  const serialized = JSON.stringify(input ?? []);
  const userTexts = Array.isArray(input)
    ? input
        .filter((item) => item !== null && typeof item === "object" && item.role === "user")
        .map((item) =>
          typeof item.content === "string"
            ? item.content
            : Array.isArray(item.content)
              ? item.content.map((block) => block?.text ?? "").join("\n")
              : "",
        )
    : [];
  return {
    originalPromptCount: [...serialized.matchAll(/JA_RECOVERY_(?:CONTINUE|TURN_1)/gu)].length,
    continueMessageCount: userTexts.filter((text) => text.trim() === "继续").length,
    replayedToolCallCount: [...serialized.matchAll(/"type":"function_call"/gu)].length,
    functionCallOutputCount: Array.isArray(input)
      ? input.filter((item) => item?.type === "function_call_output").length
      : 0,
    step,
  };
}

/** 构造完整 function_call SSE，让 loopback 真窗可以检查 Tool 结果提交后的请求历史。 */
function functionCallStream(id) {
  const item = {
    id: `fc_${id}`,
    type: "function_call",
    call_id: RECOVERY_TOOL_CALL_ID,
    name: "shell",
    arguments: JSON.stringify({ command: "Write-Output JA_RECOVERY_TOOL_ONCE_EXECUTED", timeout_ms: 5_000 }),
  };
  const response = {
    id: `resp_${id}`,
    object: "response",
    created_at: 0,
    model: "review-e2e",
    status: "completed",
    output: [item],
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    usage: {
      input_tokens: 300,
      output_tokens: 12,
      total_tokens: 312,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 },
    },
  };
  const frames = [
    ["response.created", { response: { ...response, status: "in_progress", output: [], usage: undefined } }],
    ["response.output_item.added", { output_index: 0, item: { ...item, arguments: "" } }],
    ["response.function_call_arguments.done", { item_id: item.id, output_index: 0, arguments: item.arguments }],
    ["response.output_item.done", { output_index: 0, item }],
    ["response.completed", { response }],
  ];
  return frames
    .map(([type, payload], sequence_number) =>
      `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number, ...payload })}\n\n`,
    )
    .join("");
}

/** 返回带首个正文 delta 但没有 terminal event 的 SSE，用于确认重试从干净历史重生。 */
function truncatedTextStream(id) {
  const response = {
    id: `resp_${id}`,
    object: "response",
    created_at: 0,
    model: "review-e2e",
    status: "in_progress",
    output: [],
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
  };
  return [
    ["response.created", { response }],
    [
      "response.output_text.delta",
      {
        delta: "JA_RECOVERY_PARTIAL_DRAFT_MUST_NOT_REPEAT",
        item_id: `msg_${id}`,
        output_index: 0,
        content_index: 0,
        logprobs: [],
      },
    ],
  ]
    .map(([type, payload], sequence_number) =>
      `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number, ...payload })}\n\n`,
    )
    .join("");
}

/** 只启动随机 loopback listener；按每个原始 user marker 记录请求序号以检验六次预算。 */
export async function startRecoveryFixture() {
  const attempts = [];
  const requestCounts = new Map();
  let summaryFailure = true;
  let gateReleased = false;
  let releaseGate;
  const gate = new Promise((done) => {
    releaseGate = done;
  });
  const server = createServer(async (request, response) => {
    try {
      assert.equal(request.method, "POST");
      assert.equal(request.url, "/v1/responses");
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        assert.ok(size <= 4 * 1024 * 1024);
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const step = requestStep(payload);
      const requestNumber = (requestCounts.get(step) ?? 0) + 1;
      requestCounts.set(step, requestNumber);
      const serializedInput = JSON.stringify(payload.input ?? []);
      const functionCallOutputCount = Array.isArray(payload.input)
        ? payload.input.filter(
            (item) => item?.type === "function_call_output" && item.call_id === RECOVERY_TOOL_CALL_ID,
          ).length
        : 0;
      const functionCallCount = [...serializedInput.matchAll(/"type":"function_call"/gu)].length;
      const retryAttempt = ((requestNumber - 1) % RECOVERY_MAX_ATTEMPTS) + 1;
      const logicalTurn = Math.floor((requestNumber - 1) / RECOVERY_MAX_ATTEMPTS) + 1;
      let status = 200;
      let body;
      let mode = "success";

      if (step === "JA_RECOVERY_TOOL_ONCE" && requestNumber > 1) {
        assert.equal(functionCallCount, 1, "tool retry must retain exactly one committed function_call");
        assert.equal(functionCallOutputCount, 1, "tool retry must reuse exactly one committed function_call_output");
      }

      if (["1", "2", "3"].includes(step)) {
        status = 503;
        mode = "legacy-unavailable";
      } else if (step === "summary" && summaryFailure) {
        status = 503;
        mode = "summary-unavailable";
      } else if (step === "JA_RECOVERY_RETRY_SUCCESS" && requestNumber <= 5) {
        status = 503;
        mode = "retry-success-sequence";
      } else if (step === "JA_RECOVERY_RETRY_EXHAUSTED" && requestNumber <= RECOVERY_MAX_ATTEMPTS) {
        status = 503;
        mode = "retry-exhaustion-sequence";
      } else if (step === "JA_RECOVERY_CONTINUE" && requestNumber <= RECOVERY_MAX_ATTEMPTS) {
        status = 503;
        mode = "manual-continue-sequence";
      } else if (step === "JA_RECOVERY_REASK_ORIGINAL" && requestNumber <= RECOVERY_MAX_ATTEMPTS) {
        status = 503;
        mode = "reask-source-failure";
      } else if (step === "JA_RECOVERY_PARTIAL" && requestNumber === 1) {
        mode = "truncated-after-delta";
        body = truncatedTextStream(`partial_${requestNumber}`);
      } else if (step === "JA_RECOVERY_CANCEL_BACKOFF" && requestNumber <= 4) {
        status = 503;
        mode = "cancel-backoff";
      } else if (step === "JA_RECOVERY_BAD_REQUEST") {
        status = 400;
        mode = "deterministic-rejection";
      } else if (step === "JA_RECOVERY_TOOL_ONCE" && requestNumber === 1) {
        mode = "single-tool-call";
        body = functionCallStream(`tool_${requestNumber}`);
      } else if (step === "JA_RECOVERY_TOOL_ONCE" && requestNumber === 2) {
        status = 503;
        mode = "tool-result-retry";
      }

      const attempt = {
        step,
        requestNumber,
        retryAttempt,
        logicalTurn,
        status,
        mode,
        functionCallCount,
        functionCallOutputCount,
        ...(step === "continue" || step === "JA_RECOVERY_CONTINUE"
          ? { continuationContext: continuationContext(payload, step) }
          : {}),
      };
      attempts.push(attempt);

      if (status !== 200) {
        response.writeHead(status, { "content-type": "application/json" });
        response.end(
          JSON.stringify({
            error: {
              type: status === 400 ? "invalid_request_error" : "server_error",
              code: status === 400 ? "fixture_invalid_value" : "fixture_unavailable",
              ...(status === 400 ? { param: "fixture.input" } : {}),
              message: status === 400 ? "JA_FIXTURE_DETERMINISTIC_REJECTION" : "JA_RECOVERY_UPSTREAM_FAILURE",
            },
          }),
        );
        return;
      }

      if (step === "JA_RECOVERY_CONTINUE" && requestNumber > RECOVERY_MAX_ATTEMPTS && !gateReleased) {
        await gate;
      }
      if (body === undefined) {
        const successText =
          step === "summary"
            ? summaryDocument(payload)
            : step === "title"
              ? "恢复验收"
              : step === "continue"
                ? `JA_RECOVERY_CONTINUE_SUCCESS_${logicalTurn}`
                : step === "JA_RECOVERY_CONTINUE"
                  ? "JA_RECOVERY_CONTINUE_SUCCESS"
                  : step === "JA_RECOVERY_RETRY_SUCCESS"
                    ? "JA_RECOVERY_RETRY_SUCCESS_AFTER_FIVE"
                    : step === "JA_RECOVERY_PARTIAL"
                      ? "JA_RECOVERY_PARTIAL_RETRY_SUCCESS"
                      : step === "JA_RECOVERY_TOOL_ONCE"
                        ? "JA_RECOVERY_TOOL_ONCE_SUCCESS"
                        : step === "JA_RECOVERY_CANCEL_BACKOFF"
                          ? "JA_RECOVERY_CANCEL_BACKOFF_RECOVERED"
                          : step === "JA_RECOVERY_REASK_EDITED"
                            ? "JA_RECOVERY_REASK_EDITED_SUCCESS"
                            : `JA_RECOVERY_SUCCESS_${step}`;
        body = responseStream(successText, `${step}_${requestNumber}`);
      }
      response.writeHead(200, {
        "content-type": "text/event-stream",
        "cache-control": "no-store",
      });
      response.end(body);
    } catch (error) {
      attempts.push({ step: "fixture_error", message: String(error.message).slice(0, 200) });
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: String(error.message).slice(0, 200) }));
    }
  });
  await new Promise((done, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", done);
  });
  return {
    baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    attempts,
    /** 由真窗确认状态采样后释放 continue 成功答复，避免依赖固定 sleep。 */
    release() {
      gateReleased = true;
      releaseGate();
    },
    /** 在失败历史断言后恢复摘要端点，保持每个摘要 Turn 内的重试结果一致。 */
    recoverSummary() {
      summaryFailure = false;
    },
    /** 清理只关闭本 fixture 的 listener 和正在等待的 loopback 请求。 */
    async close() {
      gateReleased = true;
      releaseGate();
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    },
  };
}

/** 从服务端结构化证据提取真实来源编号，摘要不虚构历史 identity。 */
export function summaryDocument(payload) {
  assert.equal(typeof payload.input, "string", "native summary input must be a JSON string");
  const evidence = JSON.parse(payload.input);
  assert.ok(evidence?.evictedMessages.length > 0, "summary request lacks evicted evidence");
  const document = Object.fromEntries(
    [
      "goals",
      "constraints",
      "completedProgress",
      "currentProgress",
      "blockers",
      "decisions",
      "nextSteps",
      "criticalFacts",
      "files",
      "pendingEffects",
      "retirements",
    ].map((field) => [field, []]),
  );
  document.criticalFacts = evidence.evictedMessages
    .filter((message) => message.role === "user")
    .map((message) => ({
      text: `保留恢复验收用户请求 ${message.ordinal}`,
      sourceOrdinal: message.ordinal,
    }));
  return JSON.stringify(document);
}
