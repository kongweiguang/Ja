// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { collectToolResultDiagnostics, resolveLaunchSpec } from "./process-session-full-access.mjs";

/** 给每个启动规格测试提供独立非空文件，避免依赖当前 Maven 产物或触碰用户 profile。 */
async function createArtifacts(t) {
  const directory = await mkdtemp(join(tmpdir(), "ja-process-launch-spec-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const jar = join(directory, "app-server.jar");
  const executable = join(directory, "app-server.exe");
  await writeFile(jar, "jvm artifact");
  await writeFile(executable, "native artifact");
  return { jar, executable };
}

/** JVM 规格必须保留 JAR 参数形态，并使用显式选定的 Java 命令。 */
test("resolves an explicit JVM JAR launch specification", async (t) => {
  const { jar } = await createArtifacts(t);
  const launchSpec = await resolveLaunchSpec({
    JA_PROCESS_SESSION_JAR: jar,
    JA_TEST_JAVA: "java-25.exe",
  });

  assert.equal(launchSpec.kind, "jvm");
  assert.equal(launchSpec.artifactPath, resolve(jar));
  assert.equal(launchSpec.command, "java-25.exe");
  assert.deepEqual(launchSpec.prefixArgs, ["-jar", resolve(jar)]);
  assert.ok(Object.isFrozen(launchSpec));
  assert.ok(Object.isFrozen(launchSpec.prefixArgs));
});

/** Native 规格直接执行产物，不能继承 JVM 的 -jar 参数或要求 JA_TEST_JAVA。 */
test("resolves an explicit Native executable launch specification", async (t) => {
  const { executable } = await createArtifacts(t);
  const launchSpec = await resolveLaunchSpec({
    JA_PROCESS_SESSION_EXECUTABLE: executable,
    JA_TEST_JAVA: "must-not-be-used.exe",
  });

  assert.equal(launchSpec.kind, "native");
  assert.equal(launchSpec.artifactPath, resolve(executable));
  assert.equal(launchSpec.command, resolve(executable));
  assert.deepEqual(launchSpec.prefixArgs, []);
});

/** 两种产物入口同时出现时必须在文件读取和进程启动前拒绝歧义配置。 */
test("rejects simultaneous JAR and Native artifact selectors", async (t) => {
  const { jar, executable } = await createArtifacts(t);

  await assert.rejects(
    resolveLaunchSpec({ JA_PROCESS_SESSION_JAR: jar, JA_PROCESS_SESSION_EXECUTABLE: executable }),
    /mutually exclusive/u,
  );
});

/** Native 路径必须指向现存非空文件，避免把验收跑到隐式 fallback 产物。 */
test("rejects an unavailable Native executable", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "ja-process-launch-spec-"));
  t.after(() => rm(directory, { recursive: true, force: true }));

  await assert.rejects(
    resolveLaunchSpec({ JA_PROCESS_SESSION_EXECUTABLE: join(directory, "missing.exe") }),
    { code: "ENOENT" },
  );
});

/** 失败 evidence 只保留 JA-RPC 稳定错误标识，不带命令、路径、摘要或 stderr 等宿主返回文本。 */
test("projects bounded ToolResult failure diagnostics", () => {
  const events = [
    {
      method: "assistant/model-step-committed",
      params: { turnId: "turn_fixture", toolCalls: [{ callId: "call_fixture", toolName: "exec_command" }] },
    },
    {
      method: "tool/batch-committed",
      params: {
        turnId: "turn_fixture",
        results: [{
          callId: "call_fixture",
          outcome: "failed",
          errorCode: "PROCESS_SESSION_START_FAILED",
          presentation: {
            kind: "shell",
            status: "error",
            command: "sensitive command",
            summary: "sensitive summary",
            stderr: "sensitive stderr",
          },
        }],
      },
    },
  ];

  const diagnostics = collectToolResultDiagnostics(events, "turn_fixture");
  assert.deepEqual(diagnostics, [{
    callId: "call_fixture",
    operation: "exec_command",
    causeType: null,
    causeTypeAvailable: false,
    error: { code: "PROCESS_SESSION_START_FAILED", outcome: "failed" },
    presentation: { kind: "shell", status: "error" },
  }]);
  assert.equal(JSON.stringify(diagnostics).includes("sensitive"), false);
});
