// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { resolve } from "node:path";
import {
  assertCliJsonlCompletion,
  assertWaitReceipt,
  parseArguments,
  parseCliJsonl,
} from "./subagent-cli-acceptance.mjs";

const expectedCalls = [
  ["call_collaboration_warm_read", "read"],
  ["call_collaboration_spawn", "spawn_agent"],
  ["call_collaboration_wait", "wait_agent"],
  ["call_collaboration_list", "list_agents"],
  ["call_collaboration_read_child", "read"],
  ["call_collaboration_main_write", "write"],
];

/** 定时器与终态提交可竞争；超时必须保留确切子身份，不能把正常等待超时报成工具失败。 */
test("wait receipt preserves a current timeout snapshot and rejects missing or mismatched children", () => {
  const receipt = { timedOut: true, agents: [{ threadId: "thr_child", status: "running" }] };
  assert.deepEqual(assertWaitReceipt(receipt, "thr_child"), { timedOut: true, childStatus: "running" });
  assert.throws(() => assertWaitReceipt(receipt, "thr_other"), /authoritative child status/u);
  assert.throws(() => assertWaitReceipt({ ...receipt, timedOut: false }, "thr_child"), /authoritative child status/u);
  assert.deepEqual(assertWaitReceipt({ timedOut: false, agents: [{ threadId: "thr_child", status: "completed" }] }, "thr_child"), { timedOut: false, childStatus: "completed" });
});

/** 用最小完整通知流固定 exec --json 的身份闭合与六个父线程工具结果契约。 */
function successfulRecords() {
  const identity = {
    serverInstanceId: "server_fixture",
    threadId: "thr_parent_fixture",
    turnId: "turn_parent_fixture",
  };
  return [
    { version: 2, type: "accepted", ...identity },
    {
      version: 2,
      type: "notification",
      method: "assistant/model-step-committed",
      params: {
        toolCalls: expectedCalls.map(([callId, toolName]) => ({ callId, toolName })),
      },
    },
    {
      version: 2,
      type: "notification",
      method: "tool/batch-committed",
      params: {
        results: expectedCalls.map(([callId]) => ({
          callId,
          outcome: "succeeded",
          presentation: { status: "success" },
        })),
      },
    },
    {
      version: 2,
      type: "terminal",
      ...identity,
      state: "completed",
      finalText: "CODING_AGENT_COLLABORATION_MAIN_FINAL_OK",
    },
  ];
}

/** CLI JSONL 是机器协议，不允许调试文本或无身份终态混入 stdout。 */
test("CLI JSONL parser accepts only v2 records and rejects plain or malformed output", () => {
  const records = successfulRecords();
  assert.deepEqual(
    parseCliJsonl(`${records.map((record) => JSON.stringify(record)).join("\n")}\n`),
    records,
  );
  assert.throws(() => parseCliJsonl(""), /was empty/u);
  assert.throws(() => parseCliJsonl("debug output\n"), /non-JSON/u);
  assert.throws(() => parseCliJsonl('{"version":1,"type":"terminal"}\n'), /invalid v2/u);
});

/** 完成态须与 accepted 的服务、Thread、Turn 对齐，且每个预期工具恰好成功一次。 */
test("CLI JSONL completion requires one matching successful collaboration turn", () => {
  const summary = assertCliJsonlCompletion(successfulRecords());
  assert.equal(summary.threadId, "thr_parent_fixture");
  assert.equal(summary.turnId, "turn_parent_fixture");
  assert.equal(summary.state, "completed");
  assert.equal(summary.parentToolCallCount, expectedCalls.length);

  const failed = successfulRecords();
  failed[2].params.results[0].outcome = "failed";
  assert.throws(() => assertCliJsonlCompletion(failed), /successful read result/u);

  const duplicateTerminal = successfulRecords();
  duplicateTerminal.push(duplicateTerminal.at(-1));
  assert.throws(() => assertCliJsonlCompletion(duplicateTerminal), /one accepted completed Turn/u);
});

/** 三个路径必需显式指定，避免误用当前用户 JA_HOME 或未审核的默认可执行文件。 */
test("CLI acceptance arguments require each explicit binary and external evidence path once", () => {
  const options = parseArguments([
    "--cli",
    "out/ja.exe",
    "--native-executable",
    "out/ja-app-server.exe",
    "--evidence-directory",
    "C:/Temp/ja-subagent-evidence",
  ]);
  assert.equal(options.cli, resolve("out/ja.exe"));
  assert.equal(options.nativeExecutable, resolve("out/ja-app-server.exe"));
  assert.equal(options.evidenceDirectory, resolve("C:/Temp/ja-subagent-evidence"));
  assert.throws(() => parseArguments(["--cli", "ja.exe"]), /missing --native-executable/u);
  assert.throws(
    () =>
      parseArguments([
        "--cli",
        "a",
        "--cli",
        "b",
        "--native-executable",
        "n",
        "--evidence-directory",
        "e",
      ]),
    /can only be specified once/u,
  );
});
