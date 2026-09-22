// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import test from "node:test";
import {
  requestStep,
  startRecoveryFixture,
  summaryDocument,
} from "./conversation-recovery-fixture.mjs";

/** 混有历史续答与用户标记的请求只能按最新结构化用户输入分类。 */
test("request classifier excludes historical continuation and title", () => {
  assert.equal(requestStep({ input: ["JA_RECOVERY_TURN_1", "JA_RECOVERY_TURN_4"] }), "4");
  assert.equal(
    requestStep({
      input: [
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_TURN_1" }] },
        { role: "user", content: [{ type: "input_text", text: "继续" }] },
        { role: "assistant", content: "JA_RECOVERY_CONTINUE_SUCCESS_1" },
        { role: "user", content: [{ type: "input_text", text: "JA_RECOVERY_TURN_2" }] },
      ],
    }),
    "2",
  );
  assert.equal(
    requestStep({ input: "<user_request>JA_RECOVERY_TURN_4<assistant_reply>" }),
    "title",
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

/** 通过真实 HTTP 验证前三次失败与恢复，避免 fixture 自身为离线模拟计数。 */
test("loopback performs three failures then a gated successful SSE", async () => {
  const fixture = await startRecoveryFixture();
  try {
    for (let step = 1; step <= 3; step++) {
      const response = await fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        body: JSON.stringify({ input: `JA_RECOVERY_TURN_${step}` }),
      });
      assert.equal(response.status, 503);
    }
    fixture.release();
    const response = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "JA_RECOVERY_TURN_4" }),
    });
    assert.equal(response.status, 200);
    const stream = await response.text();
    assert.match(stream, /JA_RECOVERY_SUCCESS_4/u);
    assert.match(stream, /"input_tokens":300/u);
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.step),
      ["1", "2", "3", "4"],
    );
  } finally {
    await fixture.close();
  }
});

/** Provider 重试属于同一用户续答；三条 503 后下一次显式继续才可以进入新的成功尝试。 */
test("loopback keeps a failed continuation unavailable across provider retries", async () => {
  const fixture = await startRecoveryFixture();
  fixture.release();
  try {
    const first = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "继续" }),
    });
    assert.equal(first.status, 200);
    for (let retry = 0; retry < 3; retry += 1) {
      const failed = await fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        body: JSON.stringify({ input: "继续" }),
      });
      assert.equal(failed.status, 503);
    }
    const recovered = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      body: JSON.stringify({ input: "继续" }),
    });
    assert.equal(recovered.status, 200);
    assert.match(await recovered.text(), /JA_RECOVERY_CONTINUE_SUCCESS_3/u);
    assert.deepEqual(
      fixture.attempts.map((attempt) => attempt.continuationAttempt).filter(Boolean),
      [1, 2, 2, 2, 3],
    );
  } finally {
    await fixture.close();
  }
});

/** 原生摘要string输入走真实HTTP；畸形摘要也必须正常400，不能重复写headers崩溃并遗留真窗进程。 */
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
