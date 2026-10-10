// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 覆盖真实 Provider smoke 的 loopback、证据和凭据边界。 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import process from "node:process";
import test from "node:test";

import {
  assertDirectProviderCapabilities,
  assertDurableToolHistory,
  assertWindowsPowerShellSelection,
  collectToolEvidence,
  committedTerminalReply,
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  providerFailingToolTurnInput,
  providerToolTurnInput,
  readCompleteItemContent,
  userInputContainsText,
  validatedLoopbackBaseUrl,
} from "./real-provider-smoke.mjs";

/**
 * Smoke 必须同时匹配 Java 的严格接收表、Rust 的 initialize 声明表和共享 schema，
 * 否则任一生产端会在握手前拒绝本次连接或丢弃新恢复/审批事件。
 */
test("direct capabilities match Java, Rust, and schema handshake vocabulary", () => {
  const capabilities = initializeParams().capabilities;
  const handshakeSource = readFileSync(
    new URL(
      "../../ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/handler/HandshakeHandler.java",
      import.meta.url,
    ),
    "utf8",
  );
  const schema = JSON.parse(
    readFileSync(
      new URL("../../contracts/ja-rpc/v2/schema/ja-rpc-v2.schema.json", import.meta.url),
      "utf8",
    ),
  );
  const rustCatalogSource = readFileSync(
    new URL("../../crates/ja-runtime/src/app_server_process/protocol/catalog.rs", import.meta.url),
    "utf8",
  );
  for (const name of ["METHODS", "EVENTS"]) {
    const match = handshakeSource.match(
      new RegExp(`static final List<String> ${name} = List\\.of\\(([\\s\\S]*?)\\);`, "u"),
    );
    assert.ok(match, `server ${name} vocabulary is present`);
    const serverItems = [...match[1].matchAll(/"([^"]+)"/gu)].map((entry) => entry[1]);
    assert.deepEqual(capabilities[name.toLowerCase()], serverItems);
    const schemaName = name === "METHODS" ? "methodName" : "eventName";
    const schemaValues = schema?.$defs?.[schemaName]?.enum;
    assert.ok(Array.isArray(schemaValues), `schema ${schemaName} enum is present`);
    for (const capability of serverItems) {
      assert.ok(schemaValues.includes(capability), `${capability} belongs to schema ${schemaName}`);
    }
    const rustName = name === "METHODS" ? "CLIENT_METHODS" : "EVENT_METHODS";
    const rustMatch = rustCatalogSource.match(
      new RegExp(`const ${rustName}: &\\[&str\\] = &\\[([\\s\\S]*?)\\];`, "u"),
    );
    assert.ok(rustMatch, `Rust ${rustName} vocabulary is present`);
    const rustItems = [...rustMatch[1].matchAll(/"([^"]+)"/gu)].map((entry) => entry[1]);
    assert.deepEqual(capabilities[name.toLowerCase()], rustItems);
  }
  assert.deepEqual(capabilities.accessModes, ["approval_required", "full_access"]);
  assert.deepEqual(capabilities.collaborationModes, ["default", "plan"]);
  assert.deepEqual(capabilities.features, ["task_threads", "plan", "interaction"]);
  assert.equal(capabilities.events.includes("tool/started"), true);
  assert.equal(capabilities.events.includes("tool/recovery-changed"), true);
  assert.equal(capabilities.events.includes("approval/closed"), true);
  assert.equal(capabilities.events.includes("turn/retry-started"), true);
  assert.equal(capabilities.events.includes("turn/messages_received"), true);
  assert.equal(capabilities.events.includes("task/closed"), true);
  assert.equal(capabilities.methods.includes("plan/current/read"), true);
  assert.equal(
    capabilities.methods.some((method) => method.startsWith("goal/")),
    false,
  );
  assert.equal(
    capabilities.events.some((event) => event.startsWith("goal/")),
    false,
  );
  assert.equal(capabilities.methods.filter((method) => method.startsWith("plan/")).length, 16);
  assert.equal(capabilities.methods.includes("task/close"), true);
  assert.equal(capabilities.methods.includes("thread/usage/read"), true);
  assert.equal(capabilities.methods.includes("thread/mcp/read"), true);
  assert.equal(capabilities.methods.includes("turn/continue"), true);
  assert.equal(capabilities.methods.includes("turn/reask"), true);
  assert.equal(capabilities.methods.includes("turn/recovery/respond"), true);
  assert.equal(capabilities.methods.includes("configuration/restore"), false);

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
    /do not match JA-RPC v2/u,
  );
  assert.throws(
    () =>
      assertDirectProviderCapabilities({
        ...capabilities,
        events: [...capabilities.events, "unknown/updated"],
      }),
    /do not match JA-RPC v2/u,
  );
});

test("initialize envelope contains only the current configuration-free contract", () => {
  const params = initializeParams();
  assert.equal(params.protocolMajor, 2);
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

/** 修改请求携带新的不透明操作键，其格式属于严格 JA-RPC 合同的一部分。 */
test("operation keys use the frozen v2 format", () => {
  const first = createOperationKey();
  const second = createOperationKey();
  assert.match(first, /^op_[0-9a-f]{32}$/u);
  assert.match(second, /^op_[0-9a-f]{32}$/u);
  assert.notEqual(first, second);
});

/** 正常关闭时可重试服务端无副作用的 busy 拒绝，但不能重试其它错误。 */
test("graceful shutdown waits for pending Turn work within its bounded drain", async () => {
  const session = Object.create(JsonlSession.prototype);
  const responses = [
    {
      error: {
        code: -32034,
        message: "runtime has pending work",
        data: {
          category: "conflict",
          errorCode: "INVALID_STATE",
          errorId: `err_${"1".repeat(32)}`,
          retryable: false,
        },
      },
    },
    { result: { accepted: true, status: "shutting_down" } },
  ];
  const requests = [];
  let stdinClosed = false;
  session.request = async (...args) => {
    requests.push(args);
    return responses.shift();
  };
  session.child = { stdin: { end: () => (stdinClosed = true) } };
  session.exitPromise = Promise.resolve({ code: 0, signal: null });

  const exit = await session.shutdown();

  assert.equal(requests.length, 2);
  assert.deepEqual(
    requests.map(([method, params]) => [method, params]),
    [
      ["runtime/shutdown", {}],
      ["runtime/shutdown", {}],
    ],
  );
  assert.equal(stdinClosed, true);
  assert.deepEqual(exit, { code: 0, signal: null });
});

/** 终答只从 terminal 的完整持久消息读取，流式 delta 如何切分或模型如何措辞都不得影响验收。 */
test("terminal reply evidence does not depend on streaming delta boundaries", () => {
  const terminal = {
    method: "turn/terminal",
    params: {
      turnId: "turn_test",
      state: "completed",
      finalMessage: { messageId: "item_test", text: "The result is FINAL_MARKER." },
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
        params: { ...terminal.params, finalMessage: { messageId: "item_test", text: "  " } },
      },
      "turn_test",
    ),
    null,
  );
});

/** 以 code point 而非 UTF-16 code unit 分页，并拒绝停滞游标以避免隐藏的无限请求。 */
test("item content pagination preserves Unicode boundaries and requires cursor progress", async () => {
  const content = `开始\n${"中😀".repeat(32_769)}\n结束`;
  const codePoints = Array.from(content);
  const calls = [];
  const session = {
    async request(method, params) {
      assert.equal(method, "thread/item-content/read");
      assert.equal(params.threadId, "thr_fixture");
      assert.equal(params.itemId, "item_fixture");
      assert.equal(params.limitCharacters, 65_536);
      calls.push(params.offsetCharacters);
      const offset = params.offsetCharacters;
      const next = Math.min(offset + params.limitCharacters, codePoints.length);
      return {
        result: {
          itemId: params.itemId,
          offsetCharacters: offset,
          nextOffsetCharacters: next < codePoints.length ? next : null,
          totalCharacters: codePoints.length,
          truncated: next < codePoints.length,
          content: codePoints.slice(offset, next).join(""),
        },
      };
    },
  };

  assert.equal(await readCompleteItemContent(session, "thr_fixture", "item_fixture"), content);
  assert.deepEqual(calls, [0, 65_536]);

  const stalled = {
    async request() {
      return {
        result: {
          itemId: "item_fixture",
          offsetCharacters: 0,
          nextOffsetCharacters: 0,
          totalCharacters: 1,
          truncated: true,
          content: "",
        },
      };
    },
  };
  await assert.rejects(
    readCompleteItemContent(stalled, "thr_fixture", "item_fixture"),
    /cursor is invalid/u,
  );
});

/** 最坏 JSON 转义页也必须低于 JA-RPC 的 4 MiB 帧预算。 */
test("maximum item content page remains within the negotiated frame limit", async () => {
  const content = "\u0001".repeat(65_536);
  const session = {
    async request(_method, params) {
      const result = {
        itemId: params.itemId,
        offsetCharacters: 0,
        nextOffsetCharacters: null,
        totalCharacters: 65_536,
        truncated: false,
        content,
      };
      const frame = { jsonrpc: "2.0", id: "s:item-content", result };
      assert.ok(Buffer.byteLength(JSON.stringify(frame), "utf8") <= 4_194_304);
      return { result };
    },
  };

  assert.equal(await readCompleteItemContent(session, "thr_fixture", "item_fixture"), content);
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

/** Provider 文档保留调用方选择的真实推理档位，并固定 v2 必填根字段且继续禁止 Secret 进入正文。 */
test("provider documents remain v2 and secret-free for every supported API specification", () => {
  for (const api of ["openai_responses", "openai_chat_completions", "anthropic_messages"]) {
    const document = providerConfigurationDocument({
      endpoint: `http://localhost:60842/${api}/v1`,
      name: `Custom ${api}`,
      api,
      model: "loopback-test-model",
      reasoningLevel: "xhigh",
    });
    assert.equal(document.schema_version, 2);
    assert.deepEqual(document.interaction, { clarification_enabled: true });
    assert.deepEqual(document.subagents, {
      enabled: true,
      provider_id: null,
      model_id: null,
      reasoning_level: null,
    });
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
          { callId: "call_shell", toolName: "exec_command" },
        ],
      },
    },
    {
      method: "tool/batch-committed",
      params: {
        turnId: "turn_test",
        results: [
          { callId: "call_read", toolName: "read", outcome: "succeeded" },
          { callId: "call_shell", toolName: "exec_command", outcome: "succeeded" },
        ],
      },
    },
  ];
  assert.deepEqual(
    collectToolEvidence(events, "turn_test").map((entry) => entry.toolName),
    ["read", "exec_command"],
  );
  assert.throws(
    () =>
      collectToolEvidence(
        [
          {
            method: "tool/batch-committed",
            params: {
              turnId: "turn_test",
              results: [{ callId: "missing-prefix", toolName: "exec_command", outcome: "succeeded" }],
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
        toolName: "exec_command",
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
              toolName: "exec_command",
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
        toolName: "exec_command",
        callId: "call_shell_ok",
        presentation: { status: "success", exitCode: 0 },
      },
      {
        kind: "approval",
        approvalId: "appr_success",
        turnId: "turn_success",
        callId: "call_shell_ok",
        toolName: "exec_command",
        decision: "approve",
      },
      { kind: "final_answer", turnId: "turn_success", text: "SUCCESS_FINAL" },
      {
        kind: "tool_call",
        turnId: "turn_failed",
        toolName: "exec_command",
        callId: "call_shell_failed",
        presentation: { status: "error", exitCode: 23 },
      },
      {
        kind: "approval",
        approvalId: "appr_failed",
        turnId: "turn_failed",
        callId: "call_shell_failed",
        toolName: "exec_command",
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
      calls: [{ toolName: "exec_command", status: "error", exitCode: 23 }],
      finalText: "The command failed with exit code 23.",
    }),
    { items: 7, toolCalls: 1, successfulTools: 0, failedTools: 1 },
  );
  assert.throws(
    () =>
      assertDurableToolHistory(history, "thr_test", "turn_failed", "appr_failed", {
        calls: [{ toolName: "exec_command", status: "error", exitCode: 0 }],
        finalText: "The command failed with exit code 23.",
      }),
    /Turn-scoped Tool presentations/u,
  );
});
