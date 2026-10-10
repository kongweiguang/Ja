// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { startToolCatalogFixture, toolCatalogFixtureMarkers } from "./fixtures/tool-catalog.mjs";
import { REQUIRED_BASE_TOOLS, validateToolCatalogReport } from "./tool-catalog-webview2.mjs";

const TOOL_DEFINITIONS = Object.freeze([
  {
    type: "function",
    strict: true,
    name: "read",
    description: "Read a UTF-8 file",
    parameters: {
      type: "object",
      properties: { path: { type: "string" } },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    strict: true,
    name: "write",
    description: "Write a UTF-8 file",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    type: "function",
    strict: true,
    name: "edit",
    description: "Edit a UTF-8 file",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    type: "function",
    strict: true,
    name: "exec_command",
    description: "Run a command",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    type: "function",
    strict: true,
    name: "write_stdin",
    description: "Continue a process session",
    parameters: {
      type: "object",
      properties: {
        session_id: { type: "string", minLength: 1, maxLength: 128 },
        input: { type: "string", maxLength: 1_048_576 },
        close_input: { type: "boolean" },
        after_sequence: {
          type: "integer",
          minimum: 0,
          maximum: Number.MAX_SAFE_INTEGER,
          default: 0,
        },
        yield_time_ms: {
          type: "integer",
          minimum: 0,
          maximum: 10_000,
          default: 1_000,
        },
      },
      required: ["session_id"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    strict: true,
    name: "view_image",
    description: "Read an image from the workspace",
    parameters: {
      type: "object",
      properties: { path: { type: "string", minLength: 1 } },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    strict: true,
    name: "grep",
    description: "Search literal text",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string" },
        glob: { type: ["string", "null"] },
        path: { type: ["string", "null"] },
        literal: { type: ["boolean", "null"] },
        ignoreCase: { type: ["boolean", "null"] },
        context: { type: ["integer", "null"] },
        limit: { type: ["integer", "null"] },
      },
      required: ["context", "glob", "ignoreCase", "limit", "literal", "path", "pattern"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    strict: true,
    name: "find",
    description: "Find workspace files",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string" },
        path: { type: ["string", "null"] },
        limit: { type: ["integer", "null"] },
      },
      required: ["limit", "path", "pattern"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    strict: true,
    name: "ls",
    description: "List one directory level",
    parameters: {
      type: "object",
      properties: {
        path: { type: ["string", "null"] },
        maxEntries: { type: ["integer", "null"] },
      },
      required: ["maxEntries", "path"],
      additionalProperties: false,
    },
  },
]);

/** 向隔离 fixture 发送一个不含用户密钥的最小 Responses 请求。 */
async function postTurn(fixture, input) {
  return fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ input, tools: TOOL_DEFINITIONS }),
  });
}

/** 构造真实 Tool continuation 的 function_call/function_call_output 输入对。 */
function continuation(callId, name, argumentsValue, output) {
  return [
    { type: "function_call", call_id: callId, name, arguments: JSON.stringify(argumentsValue) },
    { type: "function_call_output", call_id: callId, output },
  ];
}

/** 使用 runner 的权威基础工具闭集构造最小报告，新增公开工具时同步约束假报告。 */
function validReport() {
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: "win32",
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      toolCalls: 4,
      defaultCatalog: [...REQUIRED_BASE_TOOLS],
    },
    live: {
      sequence: ["grep:error", "grep:success", "find:success", "ls:success"],
      failedGrepExpanded: true,
      actionsVisible: true,
      targetsVisible: true,
    },
    reload: {
      sameThread: true,
      sequence: ["grep:error", "grep:success", "find:success", "ls:success"],
      failedGrepExpanded: true,
      errorIdentityPreserved: true,
    },
    workspace: { findReturnsPathsOnly: true, lsIsNonRecursive: true },
    finalVisible: true,
  };
}

/** 对齐真实 Provider 的全量基础目录，避免只核对文件子集而漏掉进程与图片工具。 */
test("loopback fixture 严格回放空 grep、纠正 grep、find、ls 四次 Tool continuation", async () => {
  const fixture = await startToolCatalogFixture();
  try {
    const first = await postTurn(fixture, [
      { role: "user", content: [{ type: "input_text", text: "tool catalog" }] },
    ]);
    const firstText = await first.text();
    assert.equal(first.status, 200);
    assert.match(firstText, new RegExp(toolCatalogFixtureMarkers.invalidGrepCallId, "u"));

    const secondPromise = postTurn(
      fixture,
      continuation(
        toolCatalogFixtureMarkers.invalidGrepCallId,
        "grep",
        {
          pattern: "",
          glob: "*.txt",
          path: ".",
          literal: true,
          ignoreCase: false,
          context: 0,
          limit: 50,
        },
        "Tool field at $.pattern violates the 'minLength' constraint. Correct the arguments and retry this Tool.",
      ),
    );
    fixture.releaseInvalidCorrection();
    const secondText = await (await secondPromise).text();
    assert.match(secondText, new RegExp(toolCatalogFixtureMarkers.validGrepCallId, "u"));

    const third = await postTurn(
      fixture,
      continuation(
        toolCatalogFixtureMarkers.validGrepCallId,
        "grep",
        {
          pattern: toolCatalogFixtureMarkers.grep,
          glob: "*.txt",
          path: ".",
          literal: true,
          ignoreCase: false,
          context: 0,
          limit: 50,
        },
        "catalog/root.txt:1: JA_TOOL_CATALOG_NEEDLE",
      ),
    );
    assert.match(await third.text(), new RegExp(toolCatalogFixtureMarkers.findCallId, "u"));

    const fourth = await postTurn(
      fixture,
      continuation(
        toolCatalogFixtureMarkers.findCallId,
        "find",
        { pattern: "*.txt", path: ".", limit: 200 },
        "catalog/root.txt\ncatalog/nested/nested.txt",
      ),
    );
    assert.match(await fourth.text(), new RegExp(toolCatalogFixtureMarkers.lsCallId, "u"));

    const fifth = await postTurn(
      fixture,
      continuation(
        toolCatalogFixtureMarkers.lsCallId,
        "ls",
        { path: ".", maxEntries: 200 },
        "catalog\nno-head-untracked.txt",
      ),
    );
    assert.match(await fifth.text(), new RegExp(toolCatalogFixtureMarkers.final, "u"));
    const turns = fixture.snapshot().attempts.filter((attempt) => attempt.kind === "turn");
    assert.deepEqual(
      turns.map((attempt) => attempt.step),
      [0, 1, 2, 3, 4],
    );
    for (const name of REQUIRED_BASE_TOOLS) {
      assert.ok(turns[0].toolNames.includes(name), `${name} must be exposed`);
    }
    for (const name of ["workspace_search", "tool_search", "list_threads"]) {
      assert.equal(turns[0].toolNames.includes(name), false, `${name} must be hidden`);
    }
  } finally {
    await fixture.close();
  }
});

test("工具目录报告验证器要求基础闭集、失败展开和 reload identity", () => {
  assert.equal(validateToolCatalogReport(validReport()).status, "passed");
  const invalid = validReport();
  invalid.provider.defaultCatalog = invalid.provider.defaultCatalog.filter((name) => name !== "ls");
  assert.throws(() => validateToolCatalogReport(invalid));
  const attachmentsAvailable = validReport();
  attachmentsAvailable.provider.defaultCatalog.push("read_attachment");
  assert.equal(validateToolCatalogReport(attachmentsAvailable).status, "passed");
});
