// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import test from "node:test";
import {
  RECOVERY_MAX_ATTEMPTS,
  requestStep,
  startRecoveryFixture,
  summaryDocument,
} from "./conversation-recovery-fixture.mjs";

/** 分类始终只看最新用户项，让历史里的 marker/答案不会改写当前恢复操作。 */
test("request classifier uses the last user marker and recognizes summary/title", () => {
  assert.equal(RECOVERY_MAX_ATTEMPTS, 6);
  assert.equal(
    requestStep({ input: ["JA_RECOVERY_TURN_1", "JA_RECOVERY_TURN_4"] }),
    "4",
  );
  assert.equal(
    requestStep({
      input: [
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_RETRY_SUCCESS" }] },
        { role: "assistant", content: "JA_RECOVERY_CONTINUE_SUCCESS" },
      ],
    }),
    "JA_RECOVERY_RETRY_SUCCESS",
  );
  assert.equal(
    requestStep({
      input: [
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_CONTINUE" }] },
        { role: "assistant", content: "failed attempt" },
      ],
    }),
    "JA_RECOVERY_CONTINUE",
    "continue must reuse the source prompt without a visible continuation user item",
  );
  assert.equal(
    requestStep({
      input: [
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_REASK_ORIGINAL" }] },
        { role: "assistant", content: "failed attempt" },
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_REASK_EDITED" }] },
      ],
    }),
    "JA_RECOVERY_REASK_EDITED",
    "reask fixture follows the replacement question rather than the failed source",
  );
  assert.equal(
    requestStep({ input: "<user_request>JA_RECOVERY_TURN_4<assistant_reply>" }),
    "title",
  );
  assert.equal(
    requestStep({ instructions: "context compaction model", input: "JA_RECOVERY_TURN_4" }),
    "summary",
  );
});

/** 摘要只使用真实 evidence ordinal，并且摘要请求不能被历史 Turn 标记误分类。 */
test("summary preserves real source ordinals and bypasses turn classification", () => {
  const payload = {
    instructions: "You are Ja's context compaction model.",
    input: JSON.stringify({
      evictedMessages: [{ ordinal: 5, role: "user", blocks: [{ text: "JA_RECOVERY_TURN_1" }] }],
    }),
  };
  assert.equal(requestStep(payload), "summary");
  assert.deepEqual(JSON.parse(summaryDocument(payload)).criticalFacts, [
    { text: "保留恢复验收用户请求 5", sourceOrdinal: 5 },
  ]);
});

/** 同一个恢复 marker 的前五个 Provider 请求失败，第六个必须返回成功流。 */
test("loopback permits five failures and success on retry six", async () => {
  const fixture = await startRecoveryFixture();
  try {
    const statuses = [];
    for (let requestNumber = 1; requestNumber <= RECOVERY_MAX_ATTEMPTS; requestNumber += 1) {
      const response = await fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        body: JSON.stringify({ input: "JA_RECOVERY_RETRY_SUCCESS" }),
      });
      statuses.push(response.status);
      if (requestNumber === RECOVERY_MAX_ATTEMPTS) {
        assert.match(await response.text(), /JA_RECOVERY_RETRY_SUCCESS_AFTER_FIVE/u);
      }
    }
    assert.deepEqual(statuses, [503, 503, 503, 503, 503, 200]);
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.retryAttempt),
      [1, 2, 3, 4, 5, 6],
    );
    assert.equal(fixture.attempts[5].logicalTurn, 1);
  } finally {
    await fixture.close();
  }
});

/** 六次瞬时失败仍继续；第七次确定性拒绝才结束本次请求。 */
test("loopback keeps retrying until a deterministic rejection", async () => {
  const fixture = await startRecoveryFixture();
  try {
    for (let requestNumber = 1; requestNumber <= RECOVERY_MAX_ATTEMPTS; requestNumber += 1) {
      const response = await fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        body: JSON.stringify({ input: "JA_RECOVERY_RETRY_EXHAUSTED" }),
      });
      assert.equal(response.status, 503);
    }
    const rejected = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_RETRY_EXHAUSTED" }),
    });
    assert.equal(rejected.status, 400);
    assert.equal(fixture.attempts.length, RECOVERY_MAX_ATTEMPTS + 1);
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.requestNumber),
      [1, 2, 3, 4, 5, 6, 7],
    );
  } finally {
    await fixture.close();
  }
});

/** 确定性拒绝后，手动继续的成功响应保持挂起以采样真实运行状态。 */
test("loopback gates a manual continuation after a deterministic rejection", async () => {
  const fixture = await startRecoveryFixture();
  try {
    for (let requestNumber = 1; requestNumber <= RECOVERY_MAX_ATTEMPTS; requestNumber += 1) {
      const response = await fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        body: JSON.stringify({ input: "JA_RECOVERY_CONTINUE" }),
      });
      assert.equal(response.status, 503);
    }
    const rejected = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_CONTINUE" }),
    });
    assert.equal(rejected.status, 400);
    let settled = false;
    const gated = fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({
        input: [
          { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_CONTINUE" }] },
          { role: "assistant", content: "failed attempt" },
        ],
      }),
    }).then((response) => {
      settled = true;
      return response;
    });
    await new Promise((done) => setTimeout(done, 25));
    assert.equal(settled, false);
    fixture.release();
    const recovered = await gated;
    assert.equal(recovered.status, 200);
    assert.match(await recovered.text(), /JA_RECOVERY_CONTINUE_SUCCESS/u);
    assert.equal(fixture.attempts.at(-1)?.continuationContext?.continueMessageCount, 0);
  } finally {
    await fixture.close();
  }
});

/** 半截 delta 后的请求保留独立 usage 响应，并由第二个请求完整结束而不是拼接文本。 */
test("loopback emits an incomplete text delta followed by a clean successful response", async () => {
  const fixture = await startRecoveryFixture();
  try {
    const partial = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_PARTIAL" }),
    });
    assert.equal(partial.status, 200);
    const brokenStream = await partial.text();
    assert.match(brokenStream, /JA_RECOVERY_PARTIAL_DRAFT_MUST_NOT_REPEAT/u);
    assert.match(brokenStream, /"sequence_number":2,"delta":$/u);
    assert.doesNotMatch(brokenStream, /response\.completed/u);

    const recovered = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_PARTIAL" }),
    });
    assert.equal(recovered.status, 200);
    assert.match(await recovered.text(), /JA_RECOVERY_PARTIAL_RETRY_SUCCESS/u);
    assert.deepEqual(
      fixture.attempts.filter((attempt) => attempt.step === "JA_RECOVERY_PARTIAL").map((attempt) => attempt.mode),
      ["truncated-after-delta", "success"],
    );
  } finally {
    await fixture.close();
  }
});

/** 确定性 400 不被 loopback fixture 转成可重试 5xx 或悄悄增加交换次数。 */
test("loopback deterministic rejection is a single HTTP 400", async () => {
  const fixture = await startRecoveryFixture();
  try {
    const response = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_BAD_REQUEST" }),
    });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), {
      error: {
        type: "invalid_request_error",
        code: "fixture_invalid_value",
        param: "fixture.input",
        message: "JA_FIXTURE_DETERMINISTIC_REJECTION",
      },
    });
    assert.equal(fixture.attempts.length, 1);
    assert.equal(fixture.attempts[0].mode, "deterministic-rejection");
  } finally {
    await fixture.close();
  }
});

/** Tool continuation 重试必须带回唯一真实 ToolResult，不能重新发起第二次 shell 调用。 */
test("loopback tool call receives one output across a failed continuation retry", async () => {
  const fixture = await startRecoveryFixture();
  try {
    const first = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_TOOL_ONCE" }),
    });
    assert.equal(first.status, 200);
    assert.match(await first.text(), /call_recovery_once/u);

    const continuation = {
      input: [
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_TOOL_ONCE" }] },
        { type: "function_call", call_id: "call_recovery_once", name: "shell", arguments: "{}" },
        { type: "function_call_output", call_id: "call_recovery_once", output: "JA_RECOVERY_TOOL_ONCE_EXECUTED" },
      ],
    };
    const failedFollowUp = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify(continuation),
    });
    assert.equal(failedFollowUp.status, 503);
    const final = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify(continuation),
    });
    assert.equal(final.status, 200);
    assert.match(await final.text(), /JA_RECOVERY_TOOL_ONCE_SUCCESS/u);
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.functionCallOutputCount),
      [0, 1, 1],
    );
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.mode),
      ["single-tool-call", "tool-result-retry", "success"],
    );
  } finally {
    await fixture.close();
  }
});

/** 摘要 HTTP 接受原生 JSON string，非法摘要走 400 并保持 fixture 清理可收口。 */
test("summary HTTP accepts native JSON input and contains fixture failures", async () => {
  const fixture = await startRecoveryFixture();
  fixture.recoverSummary();
  try {
    const input = JSON.stringify({
      evictedMessages: [{ ordinal: 5, role: "user", blocks: [{ text: "fixture" }] }],
    });
    const valid = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ instructions: "context compaction model", input }),
    });
    assert.equal(valid.status, 200);
    assert.match(await valid.text(), /sourceOrdinal/u);
    const invalid = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ instructions: "context compaction model", input: [] }),
    });
    assert.equal(invalid.status, 400);
    assert.match(await invalid.text(), /native summary input/u);
  } finally {
    await fixture.close();
  }
});
