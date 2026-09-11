// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** reasoning 真窗 runner 的本地合同与 loopback Provider 测试，不启动 Tauri 或 Java。 */

import assert from "node:assert/strict";
import test from "node:test";
import {
  ALL_PROTOCOLS,
  FINAL_MARKERS,
  PATHS,
  PRIVATE_MARKERS,
  REASONING_MARKERS,
  reasoningFixtureContract,
  startReasoningProviderFixture,
} from "./fixtures/reasoning-provider.mjs";
import {
  assertOwnedTemporaryPath,
  parseArguments,
  validateReasoningReport,
  validateReasoningSuite,
} from "./reasoning-webview2.mjs";

/** 生成每个 API 的最小首轮 JSON body，保持 fixture 测试不复制 Java adapter 的内部状态机。 */
function firstRequest(protocol) {
  if (protocol === "openai_responses") {
    return {
      model: "reasoning-loopback",
      input: [{ role: "user", content: [{ type: "input_text", text: "reasoning" }] }],
    };
  }
  if (protocol === "openai_chat_completions") {
    return {
      model: "reasoning-loopback",
      stream: true,
      messages: [{ role: "user", content: "reasoning" }],
    };
  }
  return {
    model: "reasoning-loopback",
    stream: true,
    messages: [{ role: "user", content: "reasoning" }],
  };
}

/** 构造包含全部既有 assistant response 的请求，显式保留对应 API 的原生 reasoning continuation 顺序。 */
function continuationRequest(protocol, contract, responseIndex) {
  if (protocol === "openai_responses") {
    const input = [{ role: "user", content: [{ type: "input_text", text: "reasoning" }] }];
    for (let index = 0; index < responseIndex; index += 1) {
      input.push({
        type: "reasoning",
        summary: [{ type: "summary_text", text: contract.reasoningMarkers[index] }],
        encrypted_content: contract.privateMarkers.encrypted[index],
      });
      if (index < 2) {
        const name = index === 0 ? "read" : "shell";
        input.push(
          { type: "function_call", call_id: contract.toolCalls[name], name, arguments: "{}" },
          { type: "function_call_output", call_id: contract.toolCalls[name], output: "ok" },
        );
      } else {
        input.push({ role: "assistant", content: contract.finalMarkers[index] });
      }
    }
    return {
      model: "reasoning-loopback",
      input,
    };
  }
  if (protocol === "openai_chat_completions") {
    const messages = [{ role: "user", content: "reasoning" }];
    for (let index = 0; index < responseIndex; index += 1) {
      if (index < 2) {
        const name = index === 0 ? "read" : "shell";
        messages.push(
          {
            role: "assistant",
            reasoning_content: contract.reasoningMarkers[index],
            tool_calls: [
              {
                id: contract.toolCalls[name],
                type: "function",
                function: { name, arguments: "{}" },
              },
            ],
          },
          { role: "tool", tool_call_id: contract.toolCalls[name], content: "ok" },
        );
      } else {
        messages.push({
          role: "assistant",
          reasoning_content: contract.reasoningMarkers[index],
          content: contract.finalMarkers[index],
        });
      }
    }
    return {
      model: "reasoning-loopback",
      stream: true,
      messages,
    };
  }
  const messages = [{ role: "user", content: "reasoning" }];
  for (let index = 0; index < responseIndex; index += 1) {
    const content = [
      {
        type: "thinking",
        thinking: contract.reasoningMarkers[index],
        signature: contract.privateMarkers.signature[index],
      },
      { type: "redacted_thinking", data: contract.privateMarkers.redacted[index] },
    ];
    if (index < 2) {
      const name = index === 0 ? "read" : "shell";
      content.push({ type: "tool_use", id: contract.toolCalls[name], name, input: {} });
      messages.push(
        { role: "assistant", content },
        {
          role: "user",
          content: [{ type: "tool_result", tool_use_id: contract.toolCalls[name], content: "ok" }],
        },
      );
    } else {
      content.push({ type: "text", text: contract.finalMarkers[index] });
      messages.push({ role: "assistant", content });
    }
  }
  return {
    model: "reasoning-loopback",
    stream: true,
    messages,
  };
}

/** 读取 fixture response，先等 live reasoning gate 再释放，证明流确实经过 reasoning 边界。 */
async function postAndRelease(fixture, protocol, body, round) {
  const responsePromise = fetch(`${fixture.baseUrl}${PATHS[protocol].replace(/^\/v1/u, "")}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const stage = `reasoning_${protocol}:${round}`;
  const deadline = Date.now() + 5_000;
  while (!fixture.stages.includes(stage) && Date.now() < deadline) {
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 5));
  }
  assert.equal(
    fixture.stages.includes(stage),
    true,
    `${protocol} did not reach reasoning gate ${round}`,
  );
  // gate 未释放时只能观察到 reasoning 帧；若 Tool/final/completed 已出现，live UI 断言已失去意义。
  assert.equal(fixture.stages.includes(`tool_${protocol}:${round}`), false);
  assert.equal(fixture.stages.includes(`final_${protocol}:${round}`), false);
  assert.equal(fixture.stages.includes(`completed_${protocol}:${round}`), false);
  fixture.release(protocol, round);
  const response = await responsePromise;
  const responseText = await response.text();
  assert.equal(fixture.stages.includes(`completed_${protocol}:${round}`), true);
  return responseText;
}

/** 发送不完整续传请求并只返回 HTTP 状态，避免测试输出或 fixture 记录原始请求内容。 */
async function postStatus(fixture, protocol, body) {
  const response = await fetch(`${fixture.baseUrl}${PATHS[protocol].replace(/^\/v1/u, "")}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return response.status;
}

/** CLI defaults pin JDK25 and a reasoning-specific Cargo target while protocol remains opt-in. */
test("reasoning runner defaults are isolated and explicit", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    "C:\\Temp\\reasoning",
    "--jar",
    "C:\\Temp\\ja.jar",
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-reasoning-webview2$/u);
  assert.equal(parsed.protocol, undefined);
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        "C:\\Temp\\reasoning",
        "--jar",
        "C:\\Temp\\ja.jar",
        "--protocol",
        "unknown",
      ]),
    /must be one of/u,
  );
});

/** temp-path guard rejects OS temp root itself and allows only a concrete child directory. */
test("reasoning runner temp path guard is fail-closed", () => {
  assert.throws(
    () => assertOwnedTemporaryPath(process.env.TEMP ?? "C:\\Windows\\Temp", "root"),
    /child of the OS temp directory/u,
  );
  assert.match(
    assertOwnedTemporaryPath(
      `${process.env.TEMP ?? "C:\\Windows\\Temp"}\\ja-reasoning-test`,
      "child",
    ),
    /ja-reasoning-test$/u,
  );
});

/** 三种 fixture 都产生公开 reasoning、两次 Tool 和最终正文，并接受下一轮原生续传。 */
test("loopback fixtures cover all supported reasoning protocols", async () => {
  const fixture = await startReasoningProviderFixture();
  try {
    for (const protocol of ALL_PROTOCOLS) {
      const contract = reasoningFixtureContract(protocol);
      const first = await postAndRelease(fixture, protocol, firstRequest(protocol), 0);
      assert.match(first, new RegExp(REASONING_MARKERS[protocol][0], "u"));
      for (const privateMarker of Object.values(PRIVATE_MARKERS[protocol]).flatMap((value) =>
        Array.isArray(value) ? [value[0]] : [value],
      )) {
        if (privateMarker !== undefined) assert.match(first, new RegExp(privateMarker, "u"));
      }
      const second = await postAndRelease(
        fixture,
        protocol,
        continuationRequest(protocol, contract, 1),
        1,
      );
      assert.match(second, new RegExp(REASONING_MARKERS[protocol][1], "u"));
      const third = await postAndRelease(
        fixture,
        protocol,
        continuationRequest(protocol, contract, 2),
        2,
      );
      assert.match(
        third,
        new RegExp(`${REASONING_MARKERS[protocol][2]}|${FINAL_MARKERS[protocol][2]}`, "u"),
      );
      const followUp = await postAndRelease(
        fixture,
        protocol,
        continuationRequest(protocol, contract, 3),
        3,
      );
      assert.match(followUp, new RegExp(REASONING_MARKERS[protocol][3], "u"));
      assert.match(followUp, new RegExp(FINAL_MARKERS[protocol][3], "u"));
      const restart = await postAndRelease(
        fixture,
        protocol,
        continuationRequest(protocol, contract, 4),
        4,
      );
      assert.match(restart, new RegExp(REASONING_MARKERS[protocol][4], "u"));
      assert.match(restart, new RegExp(FINAL_MARKERS[protocol][4], "u"));
      const identities = {
        openai_responses: [`resp_${protocol}_4`, `resp_${protocol}_5`],
        openai_chat_completions: [`chatcmpl_${protocol}_4`, `chatcmpl_${protocol}_5`],
        anthropic_messages: [`msg_${protocol}_4`, `msg_${protocol}_5`],
      }[protocol];
      assert.match(followUp, new RegExp(identities[0], "u"));
      assert.match(restart, new RegExp(identities[1], "u"));
    }
    const attempts = fixture.snapshot().attempts;
    for (const protocol of ALL_PROTOCOLS) {
      const protocolAttempts = attempts.filter(
        (attempt) => attempt.protocol === protocol && attempt.kind === "turn",
      );
      assert.deepEqual(
        protocolAttempts.map((attempt) => attempt.round),
        [0, 1, 2, 2, 2],
      );
      assert.deepEqual(
        protocolAttempts.map((attempt) => attempt.responseIndex),
        [0, 1, 2, 3, 4],
      );
      assert.equal(
        protocolAttempts.slice(1).every((attempt) => attempt.privateContinuationSeen),
        true,
      );
      assert.equal(
        protocolAttempts.slice(1).every((attempt) => attempt.continuationSequenceVerified),
        true,
      );
      assert.deepEqual(
        protocolAttempts.slice(1).map((attempt) => attempt.continuationCount),
        [1, 2, 3, 4],
      );
    }
  } finally {
    await fixture.close();
  }
});

/** 缺失任一历史原生块或改变其顺序都必须被 fixture 拒绝，防止验收退化为任意 marker 命中。 */
test("loopback fixtures reject incomplete native continuation", async () => {
  const fixture = await startReasoningProviderFixture();
  try {
    for (const protocol of ALL_PROTOCOLS) {
      const contract = reasoningFixtureContract(protocol);
      await postAndRelease(fixture, protocol, firstRequest(protocol), 0);
      const malformed = continuationRequest(protocol, contract, 1);
      if (protocol === "openai_responses")
        malformed.input[1].encrypted_content = "wrong-encrypted-order";
      else if (protocol === "openai_chat_completions")
        malformed.messages[1].reasoning_content = "wrong-reasoning-order";
      else malformed.messages[1].content = malformed.messages[1].content.slice(0, 1);
      assert.equal(
        await postStatus(fixture, protocol, malformed),
        409,
        `${protocol} accepted incomplete native continuation`,
      );

      await postAndRelease(fixture, protocol, continuationRequest(protocol, contract, 1), 1);
      const reordered = continuationRequest(protocol, contract, 2);
      if (protocol === "openai_responses")
        [reordered.input[1], reordered.input[4]] = [reordered.input[4], reordered.input[1]];
      else if (protocol === "openai_chat_completions")
        [reordered.messages[1], reordered.messages[3]] = [
          reordered.messages[3],
          reordered.messages[1],
        ];
      else
        [reordered.messages[1], reordered.messages[3]] = [
          reordered.messages[3],
          reordered.messages[1],
        ];
      assert.equal(
        await postStatus(fixture, protocol, reordered),
        409,
        `${protocol} accepted reordered native continuation`,
      );
    }
  } finally {
    await fixture.close();
  }
});

/** report contract rejects a missing native continuation or a sequence that omits reasoning rows. */
test("reasoning report contract requires native continuation and interleaving", () => {
  const report = {
    schemaVersion: 1,
    status: "passed",
    protocol: "openai_chat_completions",
    runtime: {
      platform: "win32",
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      nativeContinuationVerified: true,
    },
    live: { sequence: ["reasoning", "tool:read", "reasoning", "tool:shell", "reasoning"] },
    reload: {
      sameThread: true,
      sequence: ["reasoning", "tool:read", "reasoning", "tool:shell", "reasoning"],
    },
    restart: { sameThread: true, nativeContinuationVerified: true, finalVisible: true },
    publicOnly: true,
    finalVisible: true,
  };
  assert.equal(validateReasoningReport(report, "openai_chat_completions"), report);
  assert.throws(
    () =>
      validateReasoningReport(
        { ...report, provider: { ...report.provider, nativeContinuationVerified: false } },
        "openai_chat_completions",
      ),
    /equal/u,
  );
  assert.throws(
    () =>
      validateReasoningReport(
        { ...report, live: { sequence: ["tool:read"] } },
        "openai_chat_completions",
      ),
    /deep-equal|equal/u,
  );
  assert.deepEqual(
    validateReasoningSuite(
      Object.fromEntries(ALL_PROTOCOLS.map((protocol) => [protocol, { ...report, protocol }])),
    ),
    Object.fromEntries(ALL_PROTOCOLS.map((protocol) => [protocol, { ...report, protocol }])),
  );
});
