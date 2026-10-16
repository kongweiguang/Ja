// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import {
  assertThreadHistory,
  decodeTaskReceipt,
  parseArguments,
} from "./coding-agent-collaboration.mjs";

/** 原生产物检查不能被 JVM 参数掩盖，否则同一验收成功不代表目标 Native 可用。 */
test("native collaboration selection rejects mixed JVM arguments", () => {
  assert.equal(
    parseArguments(["--native-executable", "C:/audit/ja-app-server.exe"]).nativeExecutable,
    "C:/audit/ja-app-server.exe",
  );
  assert.throws(() =>
    parseArguments(["--native-executable", "C:/audit/server.exe", "--jar", "C:/audit/server.jar"]),
  );
  assert.throws(() => parseArguments(["--native-executable"]));
});

/** 构造与生产完整投影相同的长度和摘要尾注，避免用纯 JSON 替身掩盖 wire 格式。 */
function projectedReceipt(receipt) {
  const content = JSON.stringify(receipt);
  const digest = createHash("sha256").update(content, "utf8").digest("hex");
  return `${content}\n[characters=${Array.from(content).length} sha256=${digest}]`;
}

/** 完成和超时必须保留真实布尔值及控制身份，不能从摘要文字推断。 */
test("decodes completed and timed-out task receipts without losing task identity", () => {
  for (const timedOut of [false, true]) {
    const receipt = {
      timedOut,
      tasks: [{ threadId: "thr_child", status: "completed", revision: 3 }],
    };
    assert.deepEqual(decodeTaskReceipt(projectedReceipt(receipt)), receipt);
  }
});

/** 正文中的换行、括号及补充字符不应被尾注分离误截取。 */
test("keeps escaped receipt text and Unicode code point length intact", () => {
  const receipt = { summary: "line\n[characters=1 sha256=invalid]\n\u{20000}" };
  assert.deepEqual(decodeTaskReceipt(projectedReceipt(receipt)), receipt);
});

/** 长度或摘要不符代表正文不是完整原始回执，不能继续满足业务断言。 */
test("rejects mutated content and incorrect character counts", () => {
  const projected = projectedReceipt({ timedOut: false, tasks: [] });
  assert.throws(() => decodeTaskReceipt(projected.replace("false", "true")));
  assert.throws(() => decodeTaskReceipt(projected.replace(/characters=\d+/u, "characters=1")));
});

/** 不接受没有审计尾注的替身、artifact-only 或残缺 JSON，防止验收绕过真实投影。 */
test("rejects absent audit footer, artifact-only projection and malformed JSON", () => {
  assert.throws(() => decodeTaskReceipt('{"timedOut":false,"tasks":[]}'));
  assert.throws(() => decodeTaskReceipt("[tool-output-artifact-only artifact=unavailable]"));
  const content = "{invalid";
  const digest = createHash("sha256").update(content, "utf8").digest("hex");
  assert.throws(() => decodeTaskReceipt(`${content}\n[characters=8 sha256=${digest}]`));
});

/** 子任务结果必须从它自己的权威历史验证，重复调用或失败不能被最终答复标记掩盖。 */
test("requires exactly one successful child tool in its own committed history", () => {
  const history = {
    threadId: "thr_child",
    revision: 1,
    items: [
      {
        kind: "tool_call",
        callId: "call_child",
        toolName: "write",
        turnId: "turn_child",
        presentation: { status: "success" },
      },
      { kind: "final_answer", turnId: "turn_child", text: "CHILD_FINAL_OK" },
    ],
  };
  const expectedCalls = [["call_child", "write"]];
  const check = (value) => assertThreadHistory(value, "thr_child", expectedCalls, "CHILD_FINAL_OK");
  assert.equal(check(history).toolCallCount, 1);
  assert.throws(() =>
    check({
      ...history,
      items: [{ ...history.items[0], presentation: { status: "error" } }, history.items[1]],
    }),
  );
  assert.throws(() => check({ ...history, items: [...history.items, history.items[0]] }));
  assert.throws(() => check({ ...history, threadId: "thr_other" }));
});
