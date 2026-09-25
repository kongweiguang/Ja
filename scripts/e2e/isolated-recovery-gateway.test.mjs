// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Unit coverage for the request budget, baseline mutation, fixed pass-through, and redaction gates. */

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, readdir, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  baselineBody,
  configuredResponsesEndpoint,
  continuationEvidence,
  createExchangeBudget,
  fixedBody,
  isolatedProviderDocument,
  matchesSeedApproval,
  requireRealRoundAuthorization,
  recoveryInitializeParams,
  safePreflightDiagnostics,
  safeRpcErrorShape,
  safeSeedTurnEvidence,
  safeGatewayFailure,
  sanitizedJavaEnvironment,
  selectConfiguredResponsesProfile,
  seedToolResultHistoryCount,
  startRecoveryGatewayBridge,
  validateUpstreamBaseUrl,
  withSelectedCredentialSecret,
} from "./isolated-recovery-gateway.mjs";

const promptMarker = "JA_RECOVERY_GATEWAY_SEED_TEST";
const callId = "call_recovery_fixture";

/** Minimal profile fixture verifies the bridge preserves the selected real wire identity. */
function providerDocument() {
  return {
    schema_version: 2,
    config_revision: 4,
    default_provider_id: "provider_test",
    default_model_id: "model_test",
    providers: [
      {
        provider_id: "provider_test",
        name: "Test",
        api: "openai_responses",
        base_url: "http://172.16.40.21:8082/custom/v1",
        credential_id: "cred_test",
        network_timeouts: { connect_timeout_ms: 5000, request_timeout_ms: 120000 },
        agent_defaults: { context: { auto_compact: true } },
        models: [
          {
            model_id: "model_test",
            model: "upstream-model",
            default_reasoning_level: "medium",
            capabilities: { context_window_tokens: 32000, max_output_tokens: 2048 },
          },
        ],
      },
    ],
    mcp_servers: [{ endpoint: "http://must-not-connect.invalid" }],
    disabled_skills: ["fixture-skill"],
  };
}

/** A failed Tool history request represents Ja's exact continuation input shape without touching a provider. */
function continuationPayload(status) {
  const output = {
    type: "function_call_output",
    call_id: callId,
    output: "Tool read failed inside the isolated workspace",
  };
  if (status !== undefined) output.status = status;
  return {
    model: "upstream-model",
    stream: true,
    store: false,
    input: [
      { role: "user", content: [{ type: "input_text", text: promptMarker }] },
      { type: "function_call", call_id: callId, name: "read", arguments: '{"path":"missing.txt"}' },
      output,
    ],
    tools: [],
  };
}

/** The go token is round-specific and cannot be inferred from a stage name. */
test("real requests require an explicit matching parent go", () => {
  assert.throws(() => requireRealRoundAuthorization("baseline", {}), /GO_BASELINE/u);
  assert.throws(
    () =>
      requireRealRoundAuthorization("fixed", { JA_ISOLATED_RECOVERY_GATEWAY_GO: "GO_BASELINE" }),
    /GO_FIXED/u,
  );
  assert.doesNotThrow(() =>
    requireRealRoundAuthorization("baseline", { JA_ISOLATED_RECOVERY_GATEWAY_GO: "GO_BASELINE" }),
  );
});

/** HTTP private gateways remain selectable while credentials and URL query data are rejected. */
test("provider endpoint validation preserves HTTP and rejects secret-bearing URL shapes", () => {
  assert.equal(
    validateUpstreamBaseUrl("http://172.16.40.21:8082/custom/v1").href,
    "http://172.16.40.21:8082/custom/v1",
  );
  assert.equal(validateUpstreamBaseUrl("https://gateway.example/v1").protocol, "https:");
  assert.throws(
    () => validateUpstreamBaseUrl("http://user:secret@gateway.invalid/v1"),
    /unsafe shape/u,
  );
  assert.throws(
    () => validateUpstreamBaseUrl("http://gateway.invalid/v1?key=secret"),
    /unsafe shape/u,
  );
});

/** Endpoint construction follows production for host-only, canonical, and reverse-proxy bases. */
test("Responses endpoint mirrors the production v1 suffix rule", () => {
  assert.equal(configuredResponsesEndpoint("http://gateway.invalid").pathname, "/v1/responses");
  assert.equal(configuredResponsesEndpoint("http://gateway.invalid/v1/").pathname, "/v1/responses");
  assert.equal(
    configuredResponsesEndpoint("http://gateway.invalid/proxy").pathname,
    "/proxy/v1/responses",
  );
  assert.equal(
    configuredResponsesEndpoint("http://gateway.invalid/proxy/v1").pathname,
    "/proxy/v1/responses",
  );
});

/** The harness keeps exactly the active Responses model and strips unrelated network surfaces. */
test("profile selection and isolated settings retain one configured Responses model", () => {
  const source = providerDocument();
  const profile = selectConfiguredResponsesProfile(source);
  assert.equal(profile.providerId, "provider_test");
  assert.equal(profile.modelId, "model_test");
  const isolated = isolatedProviderDocument(source, profile, "http://127.0.0.1:45321/v1");
  assert.equal(isolated.providers.length, 1);
  assert.equal(isolated.providers[0].base_url, "http://127.0.0.1:45321/v1");
  assert.equal(isolated.providers[0].models[0].model, "upstream-model");
  assert.deepEqual(isolated.mcp_servers, []);
  assert.deepEqual(isolated.disabled_skills, []);
  assert.equal(isolated.default_access_mode, "approval_required");
  assert.throws(
    () =>
      selectConfiguredResponsesProfile({
        ...source,
        providers: [{ ...source.providers[0], api: "anthropic_messages" }],
      }),
    /Responses profile/u,
  );
});

/** Baseline changes only the intended property, while fixed forwards the source JSON bytes unchanged. */
test("baseline inserts one incomplete status and fixed preserves exact Ja body", () => {
  const input = JSON.stringify(continuationPayload());
  const baseline = baselineBody(input, { callId, promptMarker });
  const baselinePayload = JSON.parse(baseline.body.toString("utf8"));
  const expected = continuationPayload("incomplete");
  assert.equal(baseline.inserted, true);
  assert.deepEqual(baselinePayload, expected);
  assert.equal(fixedBody(input, { callId, promptMarker }), input);
  assert.throws(
    () => fixedBody(JSON.stringify(continuationPayload("incomplete")), { callId, promptMarker }),
    /repaired/u,
  );
  assert.equal(
    baselineBody(JSON.stringify(continuationPayload("incomplete")), { callId, promptMarker })
      .inserted,
    false,
  );
});

/** A continuation without the failed Tool or with a visible Continue message never qualifies for egress. */
test("continuation evidence requires the original question and excludes a Continue bubble", () => {
  assert.equal(
    continuationEvidence(continuationPayload(), { callId, promptMarker }).eligible,
    true,
  );
  const visibleContinue = continuationPayload();
  visibleContinue.input.splice(1, 0, {
    role: "user",
    content: [{ type: "input_text", text: "继续" }],
  });
  assert.equal(continuationEvidence(visibleContinue, { callId, promptMarker }).eligible, false);
  const missingOutput = continuationPayload();
  missingOutput.input.pop();
  assert.equal(continuationEvidence(missingOutput, { callId, promptMarker }).eligible, false);
});

/** Error evidence admits only the closed status/code/param vocabulary and drops messages and URLs. */
test("gateway failures return only allowlisted status, code, and parameter", () => {
  const safe = safeGatewayFailure(
    400,
    Buffer.from(
      JSON.stringify({
        error: {
          code: "invalid_value",
          param: "input[2].status",
          message: "secret body and https://gateway.invalid",
        },
      }),
    ),
  );
  assert.deepEqual(safe, { status: 400, code: "invalid_value", param: "input[2].status" });
  assert.deepEqual(
    safeGatewayFailure(
      418,
      Buffer.from('{"error":{"code":"private_secret","param":"Bearer abc"}}'),
    ),
    { status: "unavailable", code: "unavailable", param: "unavailable" },
  );
});

/** The test sidecar cannot inherit proxy routing or Java startup option injection. */
test("Java preflight environment strips proxy and JVM override variables", () => {
  const environment = sanitizedJavaEnvironment({
    PATH: "safe-path",
    HTTP_PROXY: "http://proxy.invalid",
    https_proxy: "http://proxy.invalid",
    JAVA_TOOL_OPTIONS: "-Dhttp.proxyHost=proxy.invalid",
    _JAVA_OPTIONS: "-Djava.net.useSystemProxies=true",
    JDK_JAVA_OPTIONS: "-javaagent:unexpected.jar",
  });
  assert.deepEqual(environment, { PATH: "safe-path" });
});

/** Preflight stack extraction must preserve only Ja-owned frame positions and exception types. */
test("preflight diagnostics omit messages, paths, credentials, and non-Ja frames", () => {
  const secret = "sk-secret-fixture";
  const stderr = [
    "ERROR Unexpected JA-RPC failure errorId=err_123 type=java.lang.IllegalStateException causeType=org.sqlite.SQLiteException origin=io.github.kongweiguang.ja.runtime.turn.AgentLoop#startTurn:312",
    `java.lang.IllegalStateException: ${secret} https://gateway.invalid/private payload=hidden`,
    "\tat io.github.kongweiguang.ja.runtime.turn.AgentLoop.startTurn(AgentLoop.java:312)",
    "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
    "\tat com.vendor.Internal.call(Internal.java:91)",
  ].join("\n");
  const diagnostic = safePreflightDiagnostics(stderr);
  assert.equal(
    diagnostic,
    "rpcType=java.lang.IllegalStateException;rpcCauseType=org.sqlite.SQLiteException;rpcOrigin=io.github.kongweiguang.ja.runtime.turn.AgentLoop#startTurn:312;exception=java.lang.IllegalStateException;jaFrames=io.github.kongweiguang.ja.runtime.turn.AgentLoop.startTurn:312",
  );
  assert.doesNotMatch(diagnostic, /sk-secret-fixture|gateway\.invalid|payload|Thread|vendor/iu);
});

/** JA-RPC error diagnosis keeps only fixed field names, value classes, and allowlisted categories. */
test("RPC error shape omits the message and unknown field values", () => {
  const detail = safeRpcErrorShape({
    code: -32080,
    message: "CHECK constraint failed: turn_execution; private fixture payload",
    trace: "must-not-escape",
  });
  assert.equal(
    detail,
    "rpcErrorFields=code,message;rpcErrorValueTypes=code:integer,message:string,data:absent;rpcMessageClass=sqlite_check_constraint;rpcUnknownFieldCount=1",
  );
  assert.doesNotMatch(detail, /private|payload|must-not-escape|turn_execution/u);
});

/** Seed terminal diagnostics admit only terminal vocabulary and approval presence, never event text. */
test("seed event evidence exposes a closed terminal projection", () => {
  const turnId = "turn_fixture";
  const diagnostic = safeSeedTurnEvidence(
    [
      { method: "approval/requested", params: { turnId, reason: "private path" } },
      {
        method: "turn/terminal",
        params: { turnId, state: "failed", errorCode: "PROVIDER_FAILURE", summary: "secret" },
      },
    ],
    turnId,
  );
  assert.deepEqual(diagnostic, {
    state: "failed",
    errorCode: "PROVIDER_FAILURE",
    approvalRequested: true,
  });
  assert.deepEqual(safeSeedTurnEvidence([], turnId), {
    state: "unavailable",
    errorCode: "unavailable",
    approvalRequested: false,
  });
  assert.deepEqual(
    seedToolResultHistoryCount(
      {
        items: [
          { kind: "tool_call", turnId, callId, presentation: { status: "error" } },
          { kind: "tool_call", turnId, callId: "call_other" },
          { kind: "final_answer", turnId, callId },
        ],
      },
      turnId,
      callId,
    ),
    1,
  );
  assert.equal(seedToolResultHistoryCount({ items: "private" }, turnId, callId), "unavailable");
});

/** An approval is accepted only for the one read call on its exact generated relative path. */
test("seed approval match requires exact Thread, Tool, revision, and relative path", () => {
  const expected = {
    threadId: "thr_fixture",
    turnId: "turn_fixture",
    callId,
    relativePath: "ja-recovery-fixture-missing-call_recovery_fixture.txt",
  };
  const event = {
    method: "approval/requested",
    params: {
      threadId: expected.threadId,
      turnId: expected.turnId,
      threadRevision: 6,
      approvalId: "appr_fixture",
      callId,
      toolName: "read",
      reason: "content is intentionally ignored",
    },
  };
  const history = {
    threadId: expected.threadId,
    revision: 6,
    items: [
      {
        kind: "tool_call",
        turnId: expected.turnId,
        callId,
        toolName: "read",
        presentation: {
          kind: "read",
          status: "waiting_approval",
          relativePaths: [expected.relativePath],
        },
      },
    ],
  };
  assert.equal(matchesSeedApproval(event, history, expected), true);
  const changedPath = structuredClone(history);
  changedPath.items[0].presentation.relativePaths[0] = "other.txt";
  assert.equal(matchesSeedApproval(event, changedPath, expected), false);
  const extraCall = structuredClone(history);
  extraCall.items.push({ kind: "tool_call", turnId: expected.turnId, callId: "call_other" });
  assert.equal(matchesSeedApproval(event, extraCall, expected), false);
  assert.equal(
    matchesSeedApproval(
      { ...event, params: { ...event.params, threadRevision: 7 } },
      history,
      expected,
    ),
    false,
  );
});

/** The host Secret only reaches the local callback; result evidence carries membership flags only. */
test("credential secret access is scoped to the callback and reports booleans", async () => {
  const directory = await mkdtemp(join(tmpdir(), "ja-recovery-auth-membership-"));
  const authPath = join(directory, "auth.json");
  try {
    await writeFile(
      authPath,
      JSON.stringify({ cred_selected_fixture: "fixture-secret-value" }),
      "utf8",
    );
    const selected = await withSelectedCredentialSecret(
      authPath,
      "cred_selected_fixture",
      async (secret) => {
        assert.equal(secret, "fixture-secret-value");
        return "local_write_completed";
      },
    );
    assert.deepEqual(selected, {
      credentialIdPresent: true,
      secretAvailable: true,
      result: "local_write_completed",
    });
    const missing = await withSelectedCredentialSecret(
      authPath,
      "cred_missing_fixture",
      async () => "must-not-run",
    );
    assert.deepEqual(missing, {
      credentialIdPresent: false,
      secretAvailable: false,
      result: undefined,
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

/** Strict Java initialization requires the current server order and schema-closed names. */
test("recovery handshake uses the current JA-RPC server capability order", async () => {
  const { capabilities } = await recoveryInitializeParams();
  const methods = capabilities.methods;
  const events = capabilities.events;
  const turnStart = methods.indexOf("turn/start");
  assert.deepEqual(methods.slice(turnStart, turnStart + 4), [
    "turn/start",
    "turn/continue",
    "turn/reask",
    "turn/resume",
  ]);
  assert.equal(methods.includes("workspace/open-general"), false);
  const stateChanged = events.indexOf("turn/state-changed");
  assert.deepEqual(events.slice(stateChanged, stateChanged + 5), [
    "turn/state-changed",
    "turn/input-queue-changed",
    "turn/input-consumed",
    "turn/retry-started",
    "turn/messages_received",
  ]);
});

/** The shared ledger permits baseline then fixed exactly once, with no reuse after either reservation. */
test("two durable slots cap real HTTP exchanges across process stages", async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), "ja-recovery-budget-test-"));
  try {
    const unpaired = await createExchangeBudget(join(root, "unpaired"), "fixed");
    await assert.rejects(unpaired.reserve(), /unused second slot/u);
    const directory = join(root, "evidence");
    const baseline = await createExchangeBudget(directory, "baseline");
    assert.equal(await baseline.reserve(), 1);
    const slotNames = await readdir(directory);
    assert.deepEqual(slotNames, ["slot-1.json"]);
    const fixed = await createExchangeBudget(directory, "fixed");
    await assert.rejects(baseline.reserve(), /already consumed/u);
    await fixed.reserve();
    await assert.rejects(baseline.reserve(), /already consumed/u);
    await assert.rejects(fixed.reserve(), /unused second slot/u);
    assert.deepEqual((await readdir(directory)).sort(), ["slot-1.json", "slot-2.json"]);
    assert.equal(
      (await readFile(join(directory, "slot-1.json"), "utf8")).includes("baseline"),
      true,
    );
    assert.equal((await readFile(join(directory, "slot-2.json"), "utf8")).includes("fixed"), true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

/** A loopback upstream proves bridge counting, one-field baseline injection, and pre-egress refusal. */
test("bridge forwards one baseline exchange and blocks every later attempt locally", async () => {
  let forwarded = 0;
  let upstreamPayload;
  const upstream = createServer(async (request, response) => {
    forwarded += 1;
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    upstreamPayload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    response.writeHead(400, { "content-type": "application/json" });
    response.end(
      JSON.stringify({
        error: {
          code: "invalid_value",
          param: "input[2].status",
          message: "secret body must not be logged",
        },
      }),
    );
  });
  await new Promise((resolve) => upstream.listen(0, "127.0.0.1", resolve));
  const upstreamAddress = upstream.address();
  assert.ok(upstreamAddress && typeof upstreamAddress !== "string");
  let reservations = 0;
  const bridge = await startRecoveryGatewayBridge({
    upstreamBaseUrl: "http://127.0.0.1:" + upstreamAddress.port + "/custom/v1",
    stage: "baseline",
    budget: {
      reserve: async () => {
        reservations += 1;
      },
    },
    callId,
    promptMarker,
  });
  try {
    bridge.setRealContinuation();
    const body = JSON.stringify(continuationPayload());
    const send = () =>
      fetch(bridge.baseUrl + "/responses", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body,
        redirect: "error",
      });
    const first = await send();
    assert.equal(first.status, 400);
    assert.equal((await send()).status, 429);
    assert.equal(forwarded, 1);
    assert.equal(reservations, 1);
    assert.equal(bridge.externalExchangeCount, 1);
    assert.equal(bridge.loopbackProviderRequestCount, 2);
    assert.deepEqual(bridge.gatewayEvidence, {
      status: 400,
      code: "invalid_value",
      param: "input[2].status",
    });
    assert.equal(upstreamPayload.input[2].status, "incomplete");
    const forwardedWithoutStatus = structuredClone(upstreamPayload);
    delete forwardedWithoutStatus.input[2].status;
    assert.deepEqual(forwardedWithoutStatus, continuationPayload());
  } finally {
    await bridge.close();
    await new Promise((resolve) => upstream.close(resolve));
  }
});

/** Successful SSE content reaches the isolated runtime but stays out of gateway evidence. */
test("bridge records only status for a successful SSE response", async () => {
  const secretSse = 'data: {"type":"response.completed","text":"must-not-be-reported"}\n\n';
  const upstream = createServer((request, response) => {
    request.resume();
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end(secretSse);
  });
  await new Promise((resolve) => upstream.listen(0, "127.0.0.1", resolve));
  const upstreamAddress = upstream.address();
  assert.ok(upstreamAddress && typeof upstreamAddress !== "string");
  const bridge = await startRecoveryGatewayBridge({
    upstreamBaseUrl: "http://127.0.0.1:" + upstreamAddress.port + "/v1",
    stage: "fixed",
    budget: { reserve: async () => undefined },
    callId,
    promptMarker,
  });
  try {
    bridge.setRealContinuation();
    const response = await fetch(bridge.baseUrl + "/responses", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(continuationPayload()),
    });
    assert.equal(response.status, 200);
    assert.equal(await response.text(), secretSse);
    assert.deepEqual(bridge.gatewayEvidence, {
      status: 200,
      code: "unavailable",
      param: "unavailable",
    });
    assert.equal(bridge.externalExchangeCount, 1);
    assert.equal(bridge.loopbackProviderRequestCount, 1);
  } finally {
    await bridge.close();
    await new Promise((resolve) => upstream.close(resolve));
  }
});

/** The seed fixture reports a fixed function call and exposes only its classification metadata. */
test("seed fixture emits exactly one fixed Tool call over loopback", async () => {
  const bridge = await startRecoveryGatewayBridge({
    upstreamBaseUrl: "http://127.0.0.1:1/v1",
    stage: "baseline",
    budget: { reserve: async () => assert.fail("seed fixture cannot reserve egress") },
    callId,
    promptMarker,
  });
  try {
    const response = await fetch(bridge.baseUrl + "/responses", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        input: [{ role: "user", content: [{ type: "input_text", text: promptMarker }] }],
      }),
    });
    assert.equal(response.status, 200);
    assert.equal((await response.text()).includes('"type":"function_call"'), true);
    assert.equal(bridge.loopbackProviderRequestCount, 1);
    assert.equal(bridge.fixtureRequestClass, "seedToolCall");
    assert.equal(bridge.fixtureCallIssued, true);
    assert.equal(bridge.externalExchangeCount, 0);
  } finally {
    await bridge.close();
  }
});
