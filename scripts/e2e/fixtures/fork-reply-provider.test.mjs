// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  forkReplyActiveAnswerText,
  forkReplyLongAnswerText,
  forkReplyMarkers,
  isAutomaticTitleRequest,
  startForkReplyProviderFixture,
} from "./fork-reply-provider.mjs";

/** 测试请求只经 localhost 进入 fixture，payload 同时模拟已冻结历史和当前 prompt。 @author kongweiguang */
async function postFixture(fixture, marker, inheritedAnswers = []) {
  return fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      input: [
        {
          role: "user",
          content: [{ type: "input_text", text: [marker, ...inheritedAnswers].join(" ") }],
        },
      ],
      tools: [],
    }),
  });
}

/** 确认 provider fixture 输出多段中文、公开工作过程，并准确统计被冻结答复 marker。 @author kongweiguang */
test("serves a completed multi-paragraph answer and reports frozen answer markers", async () => {
  const fixture = await startForkReplyProviderFixture();
  try {
    const response = await postFixture(fixture, forkReplyMarkers.sourcePrompts[1], [
      forkReplyMarkers.sourceAnswers[0],
      forkReplyMarkers.sourceAnswers[1],
    ]);
    assert.equal(response.status, 200);
    const events = await response.text();
    assert.match(events, /response\.reasoning_summary_text\.delta/u);
    assert.ok(events.includes(forkReplyMarkers.publicWork[1]));
    assert.ok(events.includes(forkReplyMarkers.sourceAnswers[1]));
    assert.match(events, /第二段用于验证按回复截断/u);
    assert.match(events, /response\.completed/u);
    assert.deepEqual(fixture.snapshot()[0].sourcePromptOccurrences, [0, 1, 0]);
    assert.deepEqual(fixture.snapshot()[0].sourceAnswerOccurrences, [1, 1, 0]);
    assert.equal(fixture.snapshot()[0].kind, "agent_turn");
  } finally {
    fixture.releaseActive();
    await fixture.close();
  }
});

/** 长答复含多页 Unicode 正文，确认 fixture 的历史预览之后仍有可逐页比对的真实 UTF-8 字节。 @author kongweiguang */
test("serves a multi-page Unicode answer without replacing its exact body with a preview", async () => {
  const fixture = await startForkReplyProviderFixture();
  try {
    const response = await postFixture(fixture, forkReplyMarkers.longAnswerPrompt);
    assert.equal(response.status, 200);
    const events = await response.text();
    const frames = events
      .split("\n")
      .filter((line) => line.startsWith("data: "))
      .map((line) => JSON.parse(line.slice("data: ".length)));
    const answerDelta = frames.find(
      (frame) =>
        frame.type === "response.output_text.delta" && frame.delta === forkReplyLongAnswerText,
    );
    assert.ok(answerDelta, "provider must stream the entire long answer in a real content delta");
    const longAnswerCodePoints = Array.from(forkReplyLongAnswerText);
    assert.ok(longAnswerCodePoints.length > 65_536);
    assert.ok(longAnswerCodePoints.slice(65_526, 65_540).join("").includes("中文🙂"));
    assert.equal(longAnswerCodePoints.filter((character) => character === "🙂").length, 1);
    assert.ok(forkReplyLongAnswerText.endsWith(forkReplyMarkers.longAnswer));
    assert.equal(fixture.snapshot()[0].longAnswerPromptOccurrences, 1);
  } finally {
    fixture.releaseActive();
    await fixture.close();
  }
});

/** 标题请求可省略空 tools，但仍须同时符合专用指令与 user_request 外形才不会吞掉真实 Agent prompt。 @author kongweiguang */
test("recognizes title requests with omitted tools and rejects lookalike Agent payloads", () => {
  const titleRequest = {
    instructions: "只输出一个自然语言标题，不超过 24 个汉字。",
    input: [
      {
        role: "user",
        content: [{ type: "input_text", text: "<user_request>正文</user_request>" }],
      },
    ],
  };

  assert.equal(isAutomaticTitleRequest(titleRequest), true);
  assert.equal(isAutomaticTitleRequest({ ...titleRequest, tools: [] }), true);
  assert.equal(isAutomaticTitleRequest({ ...titleRequest, tools: [{ type: "function" }] }), false);
  assert.equal(
    isAutomaticTitleRequest({ ...titleRequest, instructions: "按 Agent 工具继续执行。" }),
    false,
  );
  assert.equal(
    isAutomaticTitleRequest({
      ...titleRequest,
      input: [{ role: "user", content: [{ type: "input_text", text: "普通模型输入" }] }],
    }),
    false,
  );
});

/** 活动 gate 保证客户端已收到未提交 delta 后测试可控制完成帧，并始终 finally 释放。 @author kongweiguang */
test("holds and releases a real partial response without losing the completed answer", async () => {
  const fixture = await startForkReplyProviderFixture();
  try {
    const response = await postFixture(fixture, forkReplyMarkers.activePrompt);
    assert.equal(response.status, 200);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let events = "";
    let partialDelivered = false;
    const readUntilPartial = (async () => {
      while (!partialDelivered) {
        const chunk = await reader.read();
        assert.equal(
          chunk.done,
          false,
          "active Provider response must remain open at the partial delta",
        );
        events += decoder.decode(chunk.value, { stream: true });
        partialDelivered = events.includes(forkReplyMarkers.activePartial);
      }
    })();
    await Promise.all([fixture.waitForActivePartial(), readUntilPartial]);
    assert.equal(events.includes(forkReplyMarkers.activeAnswer), false);
    const prefixFrames = events
      .split("\n")
      .filter((line) => line.startsWith("data: "))
      .map((line) => JSON.parse(line.slice("data: ".length)));
    const reasoningDoneBeforeGate = prefixFrames.find(
      (frame) => frame.type === "response.output_item.done" && frame.output_index === 0,
    );
    const partialDelta = prefixFrames.find(
      (frame) => frame.type === "response.output_text.delta" && frame.output_index === 1,
    );
    assert.ok(
      reasoningDoneBeforeGate,
      "reasoning output index zero must close before the gated message delta",
    );
    assert.equal(partialDelta.delta, `${forkReplyMarkers.activePartial} `);
    assert.ok(reasoningDoneBeforeGate.sequence_number < partialDelta.sequence_number);
    fixture.releaseActive();
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      events += decoder.decode(chunk.value, { stream: true });
    }
    events += decoder.decode();
    assert.ok(events.includes(forkReplyMarkers.activeAnswer));
    assert.match(events, /response\.completed/u);
    const frames = events
      .split("\n")
      .filter((line) => line.startsWith("data: "))
      .map((line) => JSON.parse(line.slice("data: ".length)));
    const outputDeltas = frames
      .filter((frame) => frame.type === "response.output_text.delta")
      .map((frame) => frame.delta)
      .join("");
    const outputDone = frames.find((frame) => frame.type === "response.output_text.done");
    const terminalMessage = frames
      .find((frame) => frame.type === "response.completed")
      .response.output.find((item) => item.type === "message");
    const sequenceNumbers = frames.map((frame) => frame.sequence_number);
    const terminalResponse = frames.find((frame) => frame.type === "response.completed").response;
    const terminalItems = frames
      .filter((frame) => frame.type === "response.output_item.done")
      .map((frame) => frame.output_index);
    assert.equal(outputDeltas, forkReplyActiveAnswerText);
    assert.equal(outputDone.text, outputDeltas);
    assert.equal(terminalMessage.content[0].text, outputDeltas);
    assert.deepEqual(
      sequenceNumbers,
      sequenceNumbers.map((_, index) => index),
    );
    assert.deepEqual(terminalItems, [0, 1]);
    assert.equal(terminalResponse.output[0].type, "reasoning");
    assert.equal(terminalResponse.output[1].type, "message");
    assert.ok(
      frames.some(
        (frame) => frame.type === "response.output_item.added" && frame.output_index === 0,
      ),
    );
    assert.ok(
      frames.some(
        (frame) => frame.type === "response.output_item.added" && frame.output_index === 1,
      ),
    );
    assert.ok(frames.some((frame) => frame.type === "response.content_part.added"));
  } finally {
    fixture.releaseActive();
    await fixture.close();
  }
});
