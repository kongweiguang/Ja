// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Unit gates for the real-provider smoke's loopback, evidence, and secret boundaries. */

import assert from "node:assert/strict";
import process from "node:process";
import test from "node:test";

import {
  assertDirectProviderCapabilities,
  assertDurableToolHistory,
  collectToolEvidence,
  initializeParams,
  providerConfigurationDocument,
  providerToolTurnInput,
  validatedLoopbackBaseUrl,
} from "./real-provider-smoke.mjs";

test("direct capabilities retain only the minimal v2 surface", () => {
  const capabilities = initializeParams().capabilities;
  assert.deepEqual(capabilities.accessModes, ["approval_required", "full_access"]);

  assert.throws(() => assertDirectProviderCapabilities({ ...capabilities, unexpectedCapability: {} }), /invalid shape/u);
  assert.throws(() => assertDirectProviderCapabilities({
    ...capabilities,
    methods: [...capabilities.methods, "unknown/list"],
  }), /do not match JA-RPC v2/u);
  assert.throws(() => assertDirectProviderCapabilities({
    ...capabilities,
    events: [...capabilities.events, "unknown/updated"],
  }), /do not match JA-RPC v2/u);
});

test("initialize envelope contains only the current configuration-free contract", () => {
  const params = initializeParams();
  assert.equal(params.protocolMajor, 2);
  assert.equal(params.protocolMinor, 0);
  assert.deepEqual(Object.keys(params).sort(), [
    "capabilities", "clientVersion", "limits", "protocolMajor", "protocolMinor",
  ]);
  for (const forbidden of ["configSnapshot", "apiKey", "minimumCompatibleMinor"]) {
    assert.equal(forbidden in params, false);
  }
});

test("provider smoke requires an explicit paid-traffic authorization before credentials", async () => {
  const previousAuthorization = process.env.JA_REAL_PROVIDER_AUTHORIZED;
  const providerKey = ["JA", "REAL", "PROVIDER", "OPENAI", "API", "KEY"].join("_");
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
  assert.throws(() => validatedLoopbackBaseUrl("http://example.test:60842/proxy"), /loopback HTTP/u);
  assert.throws(() => validatedLoopbackBaseUrl("http://localhost:60842/proxy?secret=1"), /loopback HTTP/u);
});

test("provider documents remain v3 and secret-free for all separately selected codecs", () => {
  for (const [provider, api] of [
    ["openai", "openai_responses"],
    ["anthropic", "anthropic_messages"],
  ]) {
    const document = providerConfigurationDocument({
      endpoint: `http://localhost:60842/${provider}/v1`, provider, api, model: "loopback-test-model",
    });
    assert.equal(document.schema_version, 3);
    assert.equal(document.providers[0].base_url, `http://localhost:60842/${provider}/v1`);
    assert.equal(document.providers[0].api, api);
    assert.equal(document.providers[0].models[0].model, "loopback-test-model");
    assert.equal(JSON.stringify(document).includes("secret"), false);
    assert.equal(JSON.stringify(document).includes("apiKey"), false);
  }
});

test("removed Chat Completions API cannot enter a v3 provider document", () => {
  assert.throws(() => providerConfigurationDocument({
    endpoint: "http://localhost:60842/v1",
    provider: "openai",
    api: "openai_chat_completions",
    model: "loopback-test-model",
  }), /unsupported API/u);
});

test("Tool evidence requires the exact read then approved shell sequence", () => {
  const events = [
    { method: "assistant/model-step-committed", params: { turnId: "turn_test", toolCalls: [
      { callId: "call_read", toolName: "read" },
      { callId: "call_shell", toolName: "shell" },
    ] } },
    { method: "tool/batch-committed", params: { turnId: "turn_test", results: [
    { callId: "call_read", toolName: "read", outcome: "succeeded" },
    { callId: "call_shell", toolName: "shell", outcome: "succeeded" },
  ] } }];
  assert.deepEqual(collectToolEvidence(events, "turn_test").map((entry) => entry.toolName), ["read", "shell"]);
  assert.throws(() => collectToolEvidence([{
    method: "tool/batch-committed",
    params: { turnId: "turn_test", results: [{ callId: "missing-prefix", toolName: "shell", outcome: "succeeded" }] },
  }], "turn_test"), /invalid Tool projection/u);
  assert.match(providerToolTurnInput({
    inputPath: "input.txt", inputMarker: "INPUT", shellMarker: "SHELL", finalMarker: "FINAL",
  }), /approval request/u);
});

test("durable Tool history rejects an unresolved approval", () => {
  assert.throws(() => assertDurableToolHistory({
    threadId: "thr_test",
    revision: 4,
    items: [
      { kind: "tool_call", toolName: "read", callId: "call_read" },
      { kind: "tool_call", toolName: "shell", callId: "call_shell" },
      { kind: "tool_result", toolName: "read", callId: "call_read" },
      { kind: "tool_result", toolName: "shell", callId: "call_shell" },
      { kind: "approval", approvalId: "appr_test", turnId: "turn_test", decision: null },
    ],
  }, "thr_test", "turn_test", "appr_test"), /approved shell decision/u);
});
