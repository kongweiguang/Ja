// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import { createServer } from "node:http";

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
 * 只提取 Provider 原生 input 中最后一条 user message 的文本。续答标记会保留在历史中，不能以整段
 * JSON 搜索判断当前操作；缺少结构化 message 时保留字符串 fallback 给 fixture 的最小 HTTP 用例。
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

/** HTTP 请求按当前用户输入分类，历史中的“继续”和旧 Turn 标记绝不能污染下一次操作。 */
export function requestStep(payload) {
  if (String(payload.instructions ?? "").includes("context compaction model")) return "summary";
  const input = JSON.stringify(payload.input ?? []);
  if (input.includes("<user_request>") && input.includes("<assistant_reply>")) return "title";
  const currentUserText = latestUserText(payload.input);
  if (currentUserText?.trim() === "继续") return "continue";
  return [...(currentUserText ?? input).matchAll(/JA_RECOVERY_TURN_(\d+)/gu)].at(-1)?.[1] ?? "other";
}

/**
 * 续答必须带入失败前的对话，但不能克隆原问题或由运行时重新发起旧 Tool；只保存脱敏计数，
 * 让真窗脚本验证历史语义而不把完整请求正文写进报告。
 */
function continuationContext(payload) {
  const input = JSON.stringify(payload.input ?? []);
  return {
    originalPromptCount: [...input.matchAll(/JA_RECOVERY_TURN_1/gu)].length,
    continueMessageCount: [...input.matchAll(/"继续"/gu)].length,
    replayedToolCallCount: [...input.matchAll(/"function_call"/gu)].length,
  };
}

/** 只启动随机 loopback listener；gate 让真窗在上游完成前测量动画几何，不依赖 sleep。 */
export async function startRecoveryFixture() {
  const attempts = [];
  let summaryFailure = true;
  let continuationRequest = 0;
  let release;
  const gate = new Promise((done) => {
    release = done;
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
      const currentContinuationRequest = step === "continue" ? ++continuationRequest : undefined;
      // Provider 对同一续答最多重试三次。把第二个用户续答的三条请求归入同一逻辑尝试，避免夹具
      // 在第一次 503 后给重试错误地返回成功，掩盖失败终态与“继续回复”恢复入口。
      const currentContinuationAttempt =
        currentContinuationRequest === undefined
          ? undefined
          : currentContinuationRequest === 1
            ? 1
            : currentContinuationRequest <= 4
              ? 2
              : 3;
      const fails =
        ["1", "2", "3"].includes(step) ||
        (step === "continue" && currentContinuationAttempt === 2) ||
        (step === "summary" && summaryFailure);
      attempts.push({
        step,
        status: fails ? 503 : 200,
        ...(currentContinuationAttempt === undefined
          ? {}
          : {
              continuationAttempt: currentContinuationAttempt,
              continuationContext: continuationContext(payload),
            }),
      });
      if (step === "continue" && currentContinuationAttempt === 1) await gate;
      if (fails) {
        response.writeHead(503, { "content-type": "application/json" });
        response.end(
          JSON.stringify({
            error: {
              type: "server_error",
              code: "fixture_unavailable",
              message: "JA_RECOVERY_UPSTREAM_FAILURE",
            },
          }),
        );
      } else {
        const stream = responseStream(
          step === "summary"
            ? summaryDocument(payload)
            : step === "title"
              ? "恢复验收"
              : step === "continue"
                ? `JA_RECOVERY_CONTINUE_SUCCESS_${currentContinuationAttempt}`
                : `JA_RECOVERY_SUCCESS_${step}`,
          `${step}_${attempts.length}`,
        );
        response.writeHead(200, {
          "content-type": "text/event-stream",
          "cache-control": "no-store",
        });
        response.end(stream);
      }
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
    /** 由真实 spinner 采样完成后释放成功回复，保证运行态可观察。 */
    release() {
      release();
    },
    /** 在真窗确认失败与历史未变后才恢复摘要上游，失败期间所有内部重试一致失败。 */
    recoverSummary() {
      summaryFailure = false;
    },
    /** 清理仅针对自身 listener 和连接，不影响用户进程。 */
    async close() {
      release();
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
