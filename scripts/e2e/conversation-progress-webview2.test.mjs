// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  conversationProgressFixtureMarkers,
  startConversationProgressFixture,
} from "./fixtures/conversation-progress.mjs";
import {
  parseArguments,
  validateConversationProgressReport,
} from "./conversation-progress-webview2.mjs";

/** 以完整顺序基线驱动报告合同测试，防止真窗断言退化成单一布尔状态。 */
function validReport() {
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: { platform: "win32", surface: "tauri_webview2", boundary: "jvm_jar", nativeImageVerified: false },
    provider: { kind: "deterministic_loopback", externalCalls: 0, toolCalls: 2 },
    live: {
      commentaryBeforeFirstTool: true,
      sequence: [
        "commentary",
        "tool:read",
        "commentary",
        "commentary",
        "tool:shell",
        "commentary",
      ],
      noDuplicateTools: true,
    },
    reload: {
      sameThread: true,
      sequence: [
        "commentary",
        "tool:read",
        "commentary",
        "commentary",
        "tool:shell",
        "commentary",
      ],
    },
    finalVisible: true,
  };
}

/** 等待 fixture 到达可释放阶段，确保测试不靠任意延迟并能在故障时有界失败。 */
async function waitForFixtureStage(fixture, expectedStage) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    if (fixture.stages.includes(expectedStage)) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
  }
  throw new Error(`fixture stage ${expectedStage} was not reached before the deadline`);
}

test("CLI 默认使用 JDK25 与独立 conversation-progress Cargo target", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    "C:\\Temp\\ja-progress-evidence",
    "--jar",
    "C:\\Temp\\ja-app-server.jar",
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-conversation-progress$/u);
});

test("报告必须证明实时和 reload 后的公开交错顺序", () => {
  const report = validReport();
  assert.equal(validateConversationProgressReport(report), report);
  report.reload.sequence = ["commentary", "tool:read", "tool:shell", "commentary"];
  assert.throws(() => validateConversationProgressReport(report), /interleave|deep-equal|equal/u);
});

test("loopback fixture 按 function_call_output 推进 read、shell、final 三轮", async () => {
  const fixture = await startConversationProgressFixture();
  try {
    const post = (input) =>
      fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ input }),
      });
    const firstResponse = post([
      { role: "user", content: [{ type: "input_text", text: "progress" }] },
    ]);
    await waitForFixtureStage(fixture, "text_read");
    fixture.releaseFirstText();
    const first = await (await firstResponse).text();
    assert.match(first, /response\.output_text\.delta/u);
    assert.doesNotMatch(first, /response\.reasoning_summary_text\.delta/u);
    assert.match(first, /call_progress_read/u);
    const second = await (
      await post([
        { type: "function_call", call_id: "call_progress_read", name: "read", arguments: "{}" },
        { type: "function_call_output", call_id: "call_progress_read", output: "no head" },
      ])
    ).text();
    assert.match(second, /call_progress_shell/u);
    const third = await (
      await post([
        { type: "function_call", call_id: "call_progress_shell", name: "shell", arguments: "{}" },
        { type: "function_call_output", call_id: "call_progress_shell", output: "JA_PROGRESS_SHELL_OK" },
      ])
    ).text();
    assert.match(third, new RegExp(conversationProgressFixtureMarkers.final, "u"));
    assert.deepEqual(
      fixture.snapshot().attempts.filter((attempt) => attempt.kind === "turn").map((attempt) => attempt.step),
      [0, 1, 2],
    );
  } finally {
    await fixture.close();
  }
});
