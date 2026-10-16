// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { resolve } from "node:path";
import { parseArguments, reopenIsolatedWorkspace, findTerminalAcrossSessions, assertSingleApprovedWrite } from "./subagent-approval-native.mjs";

/** 冷重启不能丢掉停机前的父终态，也不能虚构新连接已重放旧通知。 */
test("cold approval keeps terminal evidence across runtime generations", () => {
  const terminal = { method: "turn/terminal", params: { turnId: "turn_parent", state: "completed" } };
  assert.equal(findTerminalAcrossSessions([{ events: [terminal] }, { events: [] }], "turn_parent"), terminal);
  assert.equal(findTerminalAcrossSessions([{ events: [terminal] }], "turn_other"), undefined);
});

/** Windows 原生 CRLF 不改变单次写入含义，但两次 marker 必须被发现。 */
test("approved side effect is exactly one marker with native line endings", () => {
  assertSingleApprovedWrite("NATIVE_APPROVED_TOOL_EXECUTED\r\n");
  assertSingleApprovedWrite("NATIVE_APPROVED_TOOL_EXECUTED\n");
  assert.throws(() => assertSingleApprovedWrite("NATIVE_APPROVED_TOOL_EXECUTED\r\nNATIVE_APPROVED_TOOL_EXECUTED\r\n"));
});

/** 验收必须显式绑定冻结 Native 和外部证据目录，不允许静默落回 JVM 或仓库内写证据。 */
test("Native approval arguments require explicit binary and evidence paths", () => {
  const options = parseArguments([
    "--native-executable",
    "target-subagent-closure-20261015/ja-app-server.exe",
    "--evidence-directory",
    "C:/Temp/ja-subagent-approval-evidence",
  ]);
  assert.equal(options.nativeExecutable, resolve("target-subagent-closure-20261015/ja-app-server.exe"));
  assert.equal(options.evidenceDirectory, resolve("C:/Temp/ja-subagent-approval-evidence"));
});

/** 产物期望 SHA 由本次冻结物传入，脚本只记录实测值而不锁死后续 Native 构建。 */
test("Native approval accepts an optional validated expected artifact digest", () => {
  const digest = "a".repeat(64);
  const options = parseArguments([
    "--native-executable",
    "ja-app-server.exe",
    "--evidence-directory",
    "C:/Temp/evidence",
    "--expected-native-sha256",
    digest,
  ]);
  assert.equal(options.expectedNativeSha256, digest);
  assert.throws(
    () =>
      parseArguments([
        "--native-executable",
        "ja-app-server.exe",
        "--evidence-directory",
        "C:/Temp/evidence",
        "--expected-native-sha256",
        "not-a-digest",
      ]),
    /64-character SHA-256/u,
  );
});

/** 重复或缺失产物参数会造成 Native 证据身份含糊，因此在启动进程前失败。 */
test("Native approval arguments reject duplicate, missing, and unknown paths", () => {
  assert.throws(() => parseArguments([]), /missing --native-executable/u);
  assert.throws(
    () =>
      parseArguments([
        "--native-executable",
        "a.exe",
        "--native-executable",
        "b.exe",
        "--evidence-directory",
        "evidence",
      ]),
    /can only be specified once/u,
  );
  assert.throws(
    () => parseArguments(["--native-executable", "a.exe", "--evidence-directory", "e", "--jvm"]),
    /unknown argument/u,
  );
});

/**
 * 冷场景必须显式 opt-in，避免无意把默认验收从单次热 expiry 改成两次 Native 启动。
 * @author kongweiguang
 */
test("Native approval scenarios preserve hot default and validate cold expiry modes", () => {
  const base = [
    "--native-executable",
    "ja-app-server.exe",
    "--evidence-directory",
    "C:/Temp/evidence",
  ];
  assert.equal(parseArguments(base).scenario, "hot-expiry");
  for (const scenario of ["hot-expiry", "cold-expiry", "cold-expired"]) {
    assert.equal(parseArguments([...base, "--scenario", scenario]).scenario, scenario);
  }
  assert.throws(
    () => parseArguments([...base, "--scenario", "restart-sometimes"]),
    /hot-expiry, cold-expiry, or cold-expired/u,
  );
  assert.throws(
    () => parseArguments([...base, "--scenario", "cold-expiry", "--scenario", "cold-expired"]),
    /can only be specified once/u,
  );
});

/** 重启后的 resume 前置条件必须按原物理路径重开 Workspace，并拒绝 identity 变更。 */
test("cold approval restart reopens the fixture workspace and verifies its identity", async () => {
  const workspacePath = resolve("C:/Temp/ja-native-approval-workspace");
  const directories = { workspace: workspacePath };
  const session = {
    async request(method, params, timeoutMs) {
      assert.equal(method, "workspace/open");
      assert.deepEqual(params, {
        cwd: workspacePath,
        displayName: "Ja Native approval lifecycle",
      });
      assert.equal(timeoutMs, 20_000);
      return { result: { workspaceId: "ws_original" } };
    },
  };

  const workspace = await reopenIsolatedWorkspace(session, directories, "ws_original");

  assert.equal(workspace.workspaceId, "ws_original");
  await assert.rejects(
    reopenIsolatedWorkspace(session, directories, "ws_different"),
    /identity changed across cold restart/u,
  );
});
