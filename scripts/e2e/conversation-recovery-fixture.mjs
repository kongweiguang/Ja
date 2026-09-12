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

/** HTTP 请求记录按最后一个用户标记分类，避免标题请求与历史失败标记污染重试计数。 */
export function requestStep(payload) {
  if (String(payload.instructions ?? "").includes("context compaction model")) return "summary";
  const input = JSON.stringify(payload.input ?? []);
  if (input.includes("<user_request>") && input.includes("<assistant_reply>")) return "title";
  return [...input.matchAll(/JA_RECOVERY_TURN_(\d+)/gu)].at(-1)?.[1] ?? "other";
}

/** 只启动随机 loopback listener；gate 让真窗在上游完成前测量动画几何，不依赖 sleep。 */
export async function startRecoveryFixture() {
  const attempts = [];
  let summaryFailure = true;
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
      const fails = ["1", "2", "3"].includes(step) || (step === "summary" && summaryFailure);
      attempts.push({ step, status: fails ? 503 : 200 });
      if (step === "4") await gate;
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
