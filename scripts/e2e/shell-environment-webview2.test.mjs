// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  buildEnvironmentProbeCommand,
  parseEnvironmentFacts,
  shellEnvironmentFixtureMarkers,
  startShellEnvironmentFixture,
} from "./fixtures/shell-environment.mjs";
import {
  parseArguments,
  validateShellEnvironmentReport,
} from "./shell-environment-webview2.mjs";

/** 构造最小闭集报告，合同测试只覆盖 runner 对外宣称的证据字段。 */
function validReport() {
  const facts = {
    appdata_digest: "0123456789abcdef",
    localappdata_digest: "fedcba9876543210",
    userprofile_digest: "0011223344556677",
    gh_config_dir_present: true,
    gh_installed: true,
    gh_logged_in: true,
    gh_account: "kongweiguang",
  };
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: "win32",
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0 },
    appServerShell: { transport: "app_server_shell_tool", toolCallObserved: true, facts },
    interactiveTerminal: {
      transport: "tauri_typed_ipc_conpty",
      typedInputObserved: true,
      facts,
    },
    equivalence: { sameEnvironment: true, isolatedAppData: true, comparedFields: [] },
    gh: { configDirectoryInherited: true, installed: true, loggedIn: true, account: "kongweiguang" },
    cancellation: { requested: true, providerRequestClosed: true, uiTerminalState: "cancelled" },
    secrets: { rawOutputPersisted: false, tokenOutputRequested: false },
  };
}

/** 等待 fixture 的有界请求阶段，防止协议回归被任意延迟掩盖。 */
async function waitForAttempt(fixture, predicate) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const attempt = fixture.snapshot().attempts.find(predicate);
    if (attempt !== undefined) return attempt;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
  }
  throw new Error("fixture attempt did not arrive before deadline");
}

test("CLI 默认使用 JDK25 与独立 shell-environment Cargo target", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    "C:\\Temp\\ja-shell-env-evidence",
    "--jar",
    "C:\\Temp\\ja-app-server.jar",
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-shell-environment$/u);
});

test("环境事实解析只接受 digest、布尔值和脱敏账号", () => {
  const facts = parseEnvironmentFacts(
    "JA_AGENT_ENV_BEGIN\r\n" +
      "appdata_digest=0123456789abcdef\r\n" +
      "localappdata_digest=fedcba9876543210\r\n" +
      "userprofile_digest=0011223344556677\r\n" +
      "gh_config_dir_present=True\r\n" +
      "gh_installed=True\r\n" +
      "gh_logged_in=True\r\n" +
      "gh_account=kongweiguang\r\n" +
      "JA_AGENT_ENV_END",
    "JA_AGENT_ENV",
  );
  assert.deepEqual(facts, {
    appdata_digest: "0123456789abcdef",
    localappdata_digest: "fedcba9876543210",
    userprofile_digest: "0011223344556677",
    gh_config_dir_present: true,
    gh_installed: true,
    gh_logged_in: true,
    gh_account: "kongweiguang",
  });
  assert.equal(parseEnvironmentFacts("JA_AGENT_ENV_BEGIN gh_account=token\nJA_AGENT_ENV_END", "JA_AGENT_ENV"), undefined);
});

test("PowerShell 环境 probe 使用真实 gh status 但不输出 token", () => {
  const command = buildEnvironmentProbeCommand("JA_TERMINAL_ENV");
  assert.match(command, /gh\.exe auth status --hostname github\.com/u);
  assert.doesNotMatch(command, /gh auth login|GH_TOKEN|GITHUB_TOKEN/u);
  assert.match(command, /appdata_digest/u);
  assert.match(command, /JA_TERMINAL_ENV_BEGIN/u);
});

test("loopback fixture 推进 Agent Shell continuation 并过滤原始输出", async () => {
  const fixture = await startShellEnvironmentFixture();
  try {
    const post = (input) =>
      fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ input }),
      });
    const first = await (await post([{ role: "user", content: [{ type: "input_text", text: "probe" }] }])).text();
    assert.match(first, /function_call_arguments\.done/u);
    await waitForAttempt(fixture, (attempt) => attempt.kind === "agent_shell_requested");
    const facts = {
      appdata_digest: "0123456789abcdef",
      localappdata_digest: "fedcba9876543210",
      userprofile_digest: "0011223344556677",
      gh_config_dir_present: "True",
      gh_installed: "True",
      gh_logged_in: "True",
      gh_account: "kongweiguang",
    };
    const output = [
      "JA_AGENT_ENV_BEGIN",
      ...Object.entries(facts).map(([key, value]) => `${key}=${value}`),
      "raw_token=should_be_discarded",
      "JA_AGENT_ENV_END",
    ].join("\n");
    const second = await (
      await post([
        { type: "function_call", call_id: shellEnvironmentFixtureMarkers.agentShellCallId, name: "shell", arguments: "{}" },
        {
          type: "function_call_output",
          call_id: shellEnvironmentFixtureMarkers.agentShellCallId,
          output,
        },
      ])
    ).text();
    assert.match(second, /JA_SHELL_ENV_FINAL_OK/u);
    const attempt = await waitForAttempt(fixture, (candidate) => candidate.kind === "agent_shell");
    assert.deepEqual(attempt.facts, parseEnvironmentFacts(output, "JA_AGENT_ENV"));
    assert.equal(JSON.stringify(fixture.snapshot()).includes("should_be_discarded"), false);
  } finally {
    await fixture.close();
  }
});

test("loopback fixture 优先处理带旧 Shell 历史的 cancel 请求", async () => {
  const fixture = await startShellEnvironmentFixture();
  try {
    const controller = new AbortController();
    const responsePromise = fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        input: [
          {
            type: "function_call",
            call_id: shellEnvironmentFixtureMarkers.agentShellCallId,
            name: "shell",
            arguments: "{}",
          },
          {
            type: "function_call_output",
            call_id: shellEnvironmentFixtureMarkers.agentShellCallId,
            output:
              "JA_AGENT_ENV_BEGIN\n" +
              "appdata_digest=0123456789abcdef\n" +
              "localappdata_digest=fedcba9876543210\n" +
              "userprofile_digest=0011223344556677\n" +
              "gh_config_dir_present=True\n" +
              "gh_installed=True\n" +
              "gh_logged_in=True\n" +
              "gh_account=kongweiguang\n" +
              "JA_AGENT_ENV_END",
          },
          { role: "user", content: [{ type: "input_text", text: shellEnvironmentFixtureMarkers.cancelPrompt }] },
        ],
      }),
      signal: controller.signal,
    }).then((response) => response.text());
    await waitForAttempt(fixture, (attempt) => attempt.kind === "cancel");
    controller.abort();
    await assert.rejects(responsePromise, /aborted|abort/u);
    await waitForAttempt(
      fixture,
      (attempt) => attempt.kind === "cancel" && attempt.cancelled === true,
    );
    assert.deepEqual(
      fixture.snapshot().attempts.filter((attempt) => attempt.kind !== "title").map((attempt) => attempt.kind),
      ["cancel"],
    );
  } finally {
    await fixture.close();
  }
});

test("报告缺少取消或 gh 事实时拒绝冒绿", () => {
  const report = validReport();
  assert.equal(validateShellEnvironmentReport(report), report);
  report.cancellation.providerRequestClosed = false;
  assert.throws(() => validateShellEnvironmentReport(report), /providerRequestClosed/u);
});
