// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Unit gates for the real-provider smoke's loopback, evidence, and secret boundaries. */

import assert from "node:assert/strict";
import process from "node:process";
import test from "node:test";

import {
  assertDirectProviderCapabilities,
  assertDurableToolHistory,
  assertWindowsPowerShellSelection,
  collectToolEvidence,
  committedTerminalReply,
  initializeParams,
  providerConfigurationDocument,
  providerFailingToolTurnInput,
  providerToolTurnInput,
  userInputContainsText,
  validatedLoopbackBaseUrl,
} from "./real-provider-smoke.mjs";

/** 冻结真实 Provider smoke 的完整 v1 能力词汇表，避免新增持久接口后付费验收在握手前失效。 */
test("direct capabilities retain only the minimal v1 surface", () => {
  const capabilities = initializeParams().capabilities;
  assert.deepEqual(capabilities.accessModes, ["approval_required", "full_access"]);
  assert.deepEqual(capabilities.collaborationModes, ["default", "plan"]);
  assert.deepEqual(capabilities.features, ["task_threads_v1", "plan_goal_v1", "interaction_v1"]);
  assert.equal(capabilities.events.includes("tool/started"), true);
  assert.equal(capabilities.events.includes("turn/messages_received"), true);
  assert.equal(capabilities.methods.includes("plan/current/read"), true);
  assert.equal(capabilities.methods.includes("goal/plan/attach"), true);
  assert.equal(capabilities.methods.includes("task/close"), true);

  assert.throws(
    () => assertDirectProviderCapabilities({ ...capabilities, unexpectedCapability: {} }),
    /invalid shape/u,
  );
  assert.throws(
    () =>
      assertDirectProviderCapabilities({
        ...capabilities,
        methods: [...capabilities.methods, "unknown/list"],
      }),
    /do not match JA-RPC v1/u,
  );
  assert.throws(
    () =>
      assertDirectProviderCapabilities({
        ...capabilities,
        events: [...capabilities.events, "unknown/updated"],
      }),
    /do not match JA-RPC v1/u,
  );
});

test("initialize envelope contains only the current configuration-free contract", () => {
  const params = initializeParams();
  assert.equal(params.protocolMajor, 1);
  assert.equal(params.protocolMinor, 0);
  assert.deepEqual(Object.keys(params).sort(), [
    "capabilities",
    "clientVersion",
    "limits",
    "protocolMajor",
    "protocolMinor",
  ]);
  for (const forbidden of ["configSnapshot", "apiKey", "minimumCompatibleMinor"]) {
    assert.equal(forbidden in params, false);
  }
});

/** 终答只从 terminal 的完整持久消息读取，流式 delta 如何切分或模型如何措辞都不得影响验收。 */
test("terminal reply evidence does not depend on streaming delta boundaries", () => {
  const terminal = {
    method: "turn/terminal",
    params: {
      turnId: "turn_test",
      state: "completed",
      finalMessage: { messageId: "msg_test", text: "The result is FINAL_MARKER." },
    },
  };
  assert.equal(committedTerminalReply(terminal, "turn_test"), "The result is FINAL_MARKER.");
  assert.equal(committedTerminalReply(terminal, "turn_other"), null);
  assert.equal(
    committedTerminalReply(
      { ...terminal, params: { ...terminal.params, state: "failed" } },
      "turn_test",
    ),
    null,
  );
  assert.equal(
    committedTerminalReply(
      {
        ...terminal,
        params: { ...terminal.params, finalMessage: { messageId: "msg_test", text: "  " } },
      },
      "turn_test",
    ),
    null,
  );
});

/** 历史恢复只接受当前结构化 content；旧扁平 text 即使含标记也不能让 smoke 假通过。 */
test("user input evidence follows the structured content contract", () => {
  assert.equal(
    userInputContainsText(
      {
        kind: "user_input",
        content: [
          { type: "workspace_reference", path: "ignored.txt", kind: "file" },
          { type: "text", text: "prefix EXPECTED_MARKER suffix" },
        ],
      },
      "EXPECTED_MARKER",
    ),
    true,
  );
  assert.equal(
    userInputContainsText(
      {
        kind: "user_input",
        text: "EXPECTED_MARKER",
      },
      "EXPECTED_MARKER",
    ),
    false,
  );
  assert.equal(
    userInputContainsText(
      {
        kind: "final_answer",
        content: [{ type: "text", text: "EXPECTED_MARKER" }],
      },
      "EXPECTED_MARKER",
    ),
    false,
  );
});

test("provider smoke requires an explicit paid-traffic authorization before credentials", async () => {
  const previousAuthorization = process.env.JA_REAL_PROVIDER_AUTHORIZED;
  const providerKey = ["JA", "REAL", "PROVIDER", "API", "KEY"].join("_");
  const previousKey = process.env[providerKey];
  try {
    delete process.env.JA_REAL_PROVIDER_AUTHORIZED;
    process.env[providerKey] = "fixture-value-never-sent";
    const module = await import("./real-provider-smoke.mjs");
    await assert.rejects(module.runSmoke({ silent: true }), /JA_REAL_PROVIDER_AUTHORIZED=1/u);
  } finally {
    if (previousAuthorization === undefined) delete process.env.JA_REAL_PROVIDER_AUTHORIZED;
    else process.env.JA_REAL_PROVIDER_AUTHORIZED = previousAuthorization;
    if (previousKey === undefined) delete process.env[providerKey];
    else process.env[providerKey] = previousKey;
  }
});

test("loopback validation preserves the configured provider base path", () => {
  assert.equal(
    validatedLoopbackBaseUrl("http://localhost:60842/proxy/openai/"),
    "http://localhost:60842/proxy/openai",
  );
  assert.throws(() => validatedLoopbackBaseUrl("https://localhost:60842/proxy"), /loopback HTTP/u);
  assert.throws(
    () => validatedLoopbackBaseUrl("http://example.test:60842/proxy"),
    /loopback HTTP/u,
  );
  assert.throws(
    () => validatedLoopbackBaseUrl("http://localhost:60842/proxy?secret=1"),
    /loopback HTTP/u,
  );
});

/** Provider 文档保留调用方选择的真实推理档位，同时继续禁止 Secret 进入配置正文。 */
test("provider documents remain v1 and secret-free for every supported API specification", () => {
  for (const api of ["openai_responses", "openai_chat_completions", "anthropic_messages"]) {
    const document = providerConfigurationDocument({
      endpoint: `http://localhost:60842/${api}/v1`,
      name: `Custom ${api}`,
      api,
      model: "loopback-test-model",
      reasoningLevel: "xhigh",
    });
    assert.equal(document.schema_version, 1);
    assert.equal(document.providers[0].base_url, `http://localhost:60842/${api}/v1`);
    assert.equal(document.providers[0].name, `Custom ${api}`);
    assert.equal(document.providers[0].api, api);
    assert.equal("provider" in document.providers[0], false);
    assert.equal(document.providers[0].models[0].model, "loopback-test-model");
    assert.equal(document.default_reasoning_level, "xhigh");
    assert.deepEqual(document.providers[0].models[0].reasoning_level_map, { xhigh: "xhigh" });
    assert.equal(JSON.stringify(document).includes("secret"), false);
    assert.equal(JSON.stringify(document).includes("apiKey"), false);
  }
});

test("provider documents reject an unsupported API specification", () => {
  assert.throws(
    () =>
      providerConfigurationDocument({
        endpoint: "http://localhost:60842/v1",
        name: "Custom Provider",
        api: "unsupported_api",
        model: "loopback-test-model",
      }),
    /unsupported API/u,
  );
});

test("provider documents retain independent credential references", () => {
  const first = providerConfigurationDocument({
    endpoint: "http://localhost:60842/openai/v1",
    name: "First custom provider",
    api: "openai_responses",
    model: "loopback-test-model",
    selectedCredentialId: "cred_openai_smoke",
  });
  const second = providerConfigurationDocument({
    endpoint: "http://localhost:60842/deepseek/v1",
    name: "DeepSeek",
    api: "openai_chat_completions",
    model: "deepseek-chat",
    selectedCredentialId: "cred_deepseek_smoke",
  });
  assert.equal(first.providers[0].credential_id, "cred_openai_smoke");
  assert.equal(second.providers[0].credential_id, "cred_deepseek_smoke");
  assert.notEqual(first.providers[0].credential_id, second.providers[0].credential_id);
});

test("Tool evidence requires the exact read then approved shell sequence", () => {
  const events = [
    {
      method: "assistant/model-step-committed",
      params: {
        turnId: "turn_test",
        toolCalls: [
          { callId: "call_read", toolName: "read" },
          { callId: "call_shell", toolName: "shell" },
        ],
      },
    },
    {
      method: "tool/batch-committed",
      params: {
        turnId: "turn_test",
        results: [
          { callId: "call_read", toolName: "read", outcome: "succeeded" },
          { callId: "call_shell", toolName: "shell", outcome: "succeeded" },
        ],
      },
    },
  ];
  assert.deepEqual(
    collectToolEvidence(events, "turn_test").map((entry) => entry.toolName),
    ["read", "shell"],
  );
  assert.throws(
    () =>
      collectToolEvidence(
        [
          {
            method: "tool/batch-committed",
            params: {
              turnId: "turn_test",
              results: [{ callId: "missing-prefix", toolName: "shell", outcome: "succeeded" }],
            },
          },
        ],
        "turn_test",
      ),
    /invalid Tool projection/u,
  );
  assert.match(
    providerToolTurnInput({
      inputPath: "input.txt",
      inputMarker: "INPUT",
      shellMarker: "SHELL",
      finalMarker: "FINAL",
    }),
    /approval request/u,
  );
  assert.doesNotMatch(
    providerToolTurnInput({
      inputPath: "input.txt",
      inputMarker: "INPUT",
      shellMarker: "SHELL",
      finalMarker: "FINAL",
    }),
    /Write-Output|printf/u,
  );
  const failingPrompt = providerFailingToolTurnInput({ shellMarker: "EXPECTED_FAILURE" });
  assert.match(failingPrompt, /exit 23/u);
  assert.match(failingPrompt, /do not retry/u);
});

/** Windows 方言证据必须来自模型实际持久化的命令，通用 echo 与 POSIX primitive 均不算通过。 */
test("PowerShell selection rejects cross-shell and POSIX commands", () => {
  const history = (command) => ({
    items: [
      {
        kind: "tool_call",
        turnId: "turn_test",
        toolName: "shell",
        presentation: { kind: "shell", command },
      },
    ],
  });
  assert.equal(
    assertWindowsPowerShellSelection(
      history("Write-Output 'SHELL_MARKER'"),
      "turn_test",
      "SHELL_MARKER",
    ),
    "powershell",
  );
  assert.throws(
    () =>
      assertWindowsPowerShellSelection(history("echo SHELL_MARKER"), "turn_test", "SHELL_MARKER"),
    /Windows PowerShell dialect/u,
  );
  assert.throws(
    () =>
      assertWindowsPowerShellSelection(
        history("printf '%s\\n' SHELL_MARKER"),
        "turn_test",
        "SHELL_MARKER",
      ),
    /Windows PowerShell dialect/u,
  );
});

/** 历史只保留原位更新后的 Tool 展示；审批未决时即使 Tool 成功也不能通过恢复门禁。 */
test("durable Tool history rejects an unresolved approval", () => {
  assert.throws(
    () =>
      assertDurableToolHistory(
        {
          threadId: "thr_test",
          revision: 4,
          items: [
            {
              kind: "tool_call",
              turnId: "turn_test",
              toolName: "read",
              callId: "call_read",
              presentation: { status: "success" },
            },
            {
              kind: "tool_call",
              turnId: "turn_test",
              toolName: "shell",
              callId: "call_shell",
              presentation: { status: "success" },
            },
            { kind: "approval", approvalId: "appr_test", turnId: "turn_test", decision: null },
          ],
        },
        "thr_test",
        "turn_test",
        "appr_test",
      ),
    /approved shell decision/u,
  );
});

/** 多回合历史必须按 turnId 验证，并保留失败 Shell 的稳定终态、退出码与最终回复。 */
test("durable Tool history isolates Turns and accepts one persisted failed shell", () => {
  const history = {
    threadId: "thr_test",
    revision: 9,
    items: [
      {
        kind: "tool_call",
        turnId: "turn_success",
        toolName: "read",
        callId: "call_read",
        presentation: { status: "success" },
      },
      {
        kind: "tool_call",
        turnId: "turn_success",
        toolName: "shell",
        callId: "call_shell_ok",
        presentation: { status: "success", exitCode: 0 },
      },
      {
        kind: "approval",
        approvalId: "appr_success",
        turnId: "turn_success",
        callId: "call_shell_ok",
        toolName: "shell",
        decision: "approve",
      },
      { kind: "final_answer", turnId: "turn_success", text: "SUCCESS_FINAL" },
      {
        kind: "tool_call",
        turnId: "turn_failed",
        toolName: "shell",
        callId: "call_shell_failed",
        presentation: { status: "error", exitCode: 23 },
      },
      {
        kind: "approval",
        approvalId: "appr_failed",
        turnId: "turn_failed",
        callId: "call_shell_failed",
        toolName: "shell",
        decision: "approve",
      },
      {
        kind: "final_answer",
        turnId: "turn_failed",
        text: "The command failed with exit code 23.",
      },
    ],
  };
  assert.deepEqual(
    assertDurableToolHistory(history, "thr_test", "turn_failed", "appr_failed", {
      calls: [{ toolName: "shell", status: "error", exitCode: 23 }],
      finalText: "The command failed with exit code 23.",
    }),
    { items: 7, toolCalls: 1, successfulTools: 0, failedTools: 1 },
  );
  assert.throws(
    () =>
      assertDurableToolHistory(history, "thr_test", "turn_failed", "appr_failed", {
        calls: [{ toolName: "shell", status: "error", exitCode: 0 }],
        finalText: "The command failed with exit code 23.",
      }),
    /Turn-scoped Tool presentations/u,
  );
});
