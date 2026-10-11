// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { realpath, rm, stat } from "node:fs/promises";
import { dirname } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
  agentLoopFixtureMarkers,
  assertFileSearchOutputs,
  fileSearchFixtureMarkers,
  parseFileSearchOutputs,
  sleepCommand,
  startFileSearchFixture,
} from "./fixtures/file-search-acceptance.mjs";
import {
  buildFileSearchFailureEvidence,
  cleanupAcceptanceProfile,
  parseArguments,
  followUpMailboxTask,
  respondToClarification,
  sendMailboxMessage,
  startAcceptanceTurn,
  validateFileSearchReport,
  waitForRunningApprovalProcess,
  withInteractionObservation,
} from "./file-search-acceptance.mjs";
import { createIsolatedDirectories as createProviderSmokeDirectories } from "./real-provider-smoke.mjs";

/** 覆盖所有发布平台方言，防止 Windows 开发机的 fixture 再把 PowerShell 文本发送给 zsh。 */
function approvalSleepUsesHostDialect() {
  assert.equal(sleepCommand("win32"), "Start-Sleep -Seconds 30");
  assert.equal(sleepCommand("darwin"), "sleep 30");
  assert.equal(sleepCommand("linux"), "sleep 30");
  assert.equal(agentLoopFixtureMarkers.approval.command, sleepCommand(process.platform));
}

test("approval cancellation fixture uses the host shell dialect", approvalSleepUsesHostDialect);

/** 进程登记允许晚于 Tool 投影，但取消前的 running 证据必须绑定同一个 Turn 和调用。 */
async function approvalWaitsForRealProcessRegistration() {
  const requests = [];
  const scope = {
    workspaceId: "ws_approval",
    threadId: "thr_approval",
    turnId: "turn_approval",
    callId: "call_approval",
  };
  const session = {
    events: [],
    /** 首次列表为空，第二次返回实际身份，复现冷启动期间真实进程尚未登记的窗口。 */
    async request(method, params, timeoutMs) {
      requests.push({ method, params, timeoutMs });
      return {
        result: {
          sessions:
            requests.length === 1
              ? []
              : [
                  {
                    startedTurnId: scope.turnId,
                    startedCallId: scope.callId,
                    state: "running",
                    sessionId: "proc_approval",
                  },
                ],
        },
      };
    },
  };
  assert.equal(await waitForRunningApprovalProcess(session, scope, 1_000), "proc_approval");
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.method, "process/list");
    assert.deepEqual(request.params, {
      workspaceId: scope.workspaceId,
      threadId: scope.threadId,
      startedCallId: scope.callId,
      limit: 200,
      cursor: null,
    });
    assert.ok(request.timeoutMs > 0 && request.timeoutMs <= 1_000);
  }
}

test(
  "approval cancellation waits for the exact running process",
  approvalWaitsForRealProcessRegistration,
);

/** 已结束或身份不匹配的进程不能代替真实取消对象，Turn 提前完成也必须立即拒绝。 */
async function approvalRejectsPrematureExitAndWrongIdentity() {
  const scope = {
    workspaceId: "ws_approval",
    threadId: "thr_approval",
    turnId: "turn_approval",
    callId: "call_approval",
  };
  for (const changes of [
    { state: "exited" },
    { startedTurnId: "turn_other" },
    { startedCallId: "call_other" },
  ]) {
    const session = {
      events: [],
      /** 返回同形响应但篡改单一进程事实，确认 runner 不会只接受非空列表。 */
      async request() {
        return {
          result: {
            sessions: [
              {
                startedTurnId: scope.turnId,
                startedCallId: scope.callId,
                state: "running",
                sessionId: "proc_approval",
                ...changes,
              },
            ],
          },
        };
      },
    };
    await assert.rejects(waitForRunningApprovalProcess(session, scope), assert.AssertionError);
  }
  const finished = {
    events: [{ method: "turn/terminal", params: { turnId: scope.turnId, state: "completed" } }],
    /** 空列表与已终止 Turn 一起返回，不能继续耗尽整个验收期限。 */
    async request() {
      return { result: { sessions: [] } };
    },
  };
  await assert.rejects(waitForRunningApprovalProcess(finished, scope), /remain active/u);
}

test(
  "approval cancellation rejects exited or unrelated processes",
  approvalRejectsPrematureExitAndWrongIdentity,
);

/** 以精确字段集锁定真实澄清响应合同，避免旧 idempotencyKey 再次混入验收 runner。 */
async function clarificationRespondUsesOperationKey() {
  let captured;
  const result = await respondToClarification(
    {
      async request(method, params) {
        captured = { method, params };
        return { result: { request: { status: "answered" } } };
      },
    },
    {
      threadId: "thr_clarification_contract",
      interactionId: "interaction_clarification_contract",
      expectedInteractionRevision: 4,
      answers: [{ questionId: "question_color", optionIds: [], freeText: "blue", skipped: false }],
    },
  );

  assert.equal(captured.method, "interaction/respond");
  assert.deepEqual(Object.keys(captured.params).sort(), [
    "answers",
    "expectedInteractionRevision",
    "interactionId",
    "operationKey",
    "threadId",
  ]);
  assert.equal(captured.params.threadId, "thr_clarification_contract");
  assert.equal(captured.params.interactionId, "interaction_clarification_contract");
  assert.equal(captured.params.expectedInteractionRevision, 4);
  assert.ok(typeof captured.params.operationKey === "string" && captured.params.operationKey.length > 0);
  assert.deepEqual(captured.params.answers, [
    { questionId: "question_color", optionIds: [], freeText: "blue", skipped: false },
  ]);
  assert.deepEqual(result, { request: { status: "answered" } });
}

test("clarification respond uses the current operationKey JA-RPC field set", clarificationRespondUsesOperationKey);

/** Mailbox 和 followup 使用服务端严格字段闭集，测试真实 runner 构造而非协议兼容层。 */
async function mailboxWritesUseCurrentOperationKeyFields() {
  const requests = [];
  const session = {
    async request(method, params) {
      requests.push({ method, params });
      return { result: { accepted: true } };
    },
  };
  const message = await sendMailboxMessage(session, {
    senderThreadId: "thr_parent",
    targetThreadId: "thr_child",
    content: [{ type: "text", text: "queued" }],
  });
  const followup = await followUpMailboxTask(session, {
    senderThreadId: "thr_parent",
    targetThreadId: "thr_child",
    content: [{ type: "text", text: "continue" }],
    expectedTaskRevision: 7,
  });

  assert.deepEqual(requests.map(({ method }) => method), ["thread/message/send", "task/followup"]);
  assert.deepEqual(Object.keys(requests[0].params).sort(), [
    "content",
    "operationKey",
    "senderThreadId",
    "targetThreadId",
  ]);
  assert.deepEqual(Object.keys(requests[1].params).sort(), [
    "content",
    "expectedTaskRevision",
    "operationKey",
    "senderThreadId",
    "targetThreadId",
  ]);
  for (const { params } of requests) {
    assert.equal(params.senderThreadId, "thr_parent");
    assert.equal(params.targetThreadId, "thr_child");
    assert.ok(typeof params.operationKey === "string" && params.operationKey.length > 0);
  }
  assert.notEqual(requests[0].params.operationKey, requests[1].params.operationKey);
  assert.equal(requests[1].params.expectedTaskRevision, 7);
  assert.deepEqual(message, { accepted: true });
  assert.deepEqual(followup, { accepted: true });
}

test("mailbox writes use current operationKey JA-RPC field sets", mailboxWritesUseCurrentOperationKeyFields);

/** 证明真实澄清流程先注册 Interaction 观察，再准入 Turn，最后释放观察句柄。 */
async function interactionObservationPrecedesTurnStart() {
  const threadId = "thr_observation_order";
  const requests = [];
  /** 返回 runner 可消费的确定性 RPC ACK，同时保存方法顺序用于回归断言。 */
  async function respondToRequest(method, params) {
    requests.push({ method, params });
    if (method === "interaction/observe") {
      return { result: { observationId: "observe_acceptance" } };
    }
    if (method === "turn/start") return { result: { turnId: "turn_acceptance" } };
    if (method === "interaction/unobserve") return { result: { accepted: true } };
    throw new Error(`unexpected RPC method ${method}`);
  }
  const session = { request: respondToRequest };
  /** 复用验收启动函数，使顺序检查覆盖实际 runner admission 请求。 */
  async function startTurn() {
    return startAcceptanceTurn(session, threadId, "clarification marker", "clarification");
  }

  const accepted = await withInteractionObservation(session, threadId, startTurn);

  assert.equal(accepted.turnId, "turn_acceptance");
  assert.deepEqual(
    requests.map((request) => request.method),
    ["interaction/observe", "turn/start", "interaction/unobserve"],
  );
  assert.deepEqual(requests[0].params, { threadId });
  assert.deepEqual(requests[2].params, { observationId: "observe_acceptance" });
}

test(
  "clarification registers its interaction observer before turn/start",
  interactionObservationPrecedesTurnStart,
);

/** 限时向 loopback Provider 发送无凭据请求，避免路由回归耗尽 Undici 的默认超时。 */
async function postFixture(fixture, input, tools = [{ name: "find" }, { name: "ls" }]) {
  return fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ input, tools }),
    signal: AbortSignal.timeout(5_000),
  });
}

/** 构造同一 Tool batch 的四个 function_call/function_call_output 对。 */
function continuation(outputs) {
  return fileSearchFixtureMarkers.calls.flatMap((call) => [
    {
      type: "function_call",
      call_id: call.callId,
      name: call.name,
      arguments: JSON.stringify(call.arguments),
    },
    { type: "function_call_output", call_id: call.callId, output: outputs[call.callId] },
  ]);
}

/** 构造已持久化的文件搜索历史，再追加新的当前 user 请求。 */
function mixedFileSearchHistory(currentUserText) {
  const outputs = Object.fromEntries([
    [fileSearchFixtureMarkers.calls[0].callId, "README.md\nAGENTS.md\n.codegraph\tdirectory"],
    [fileSearchFixtureMarkers.calls[1].callId, "README.md\nresult"],
    [fileSearchFixtureMarkers.calls[2].callId, "AGENTS.md\nresult"],
    [fileSearchFixtureMarkers.calls[3].callId, ".codegraph\nresult"],
  ]);
  return [
    {
      role: "user",
      content: [{ type: "input_text", text: fileSearchFixtureMarkers.user }],
    },
    ...continuation(outputs),
    { role: "user", content: [{ type: "input_text", text: currentUserText }] },
  ];
}

/** 旧主 Turn marker 即使不在最后 user 中仍路由 mailbox；快照只保留位置布尔值。 */
async function mailboxHistoryMarkerOpensGate() {
  const fixture = await startFileSearchFixture();
  try {
    const request = postFixture(
      fixture,
      [
        { role: "user", content: [{ type: "input_text", text: agentLoopFixtureMarkers.mailbox.user }] },
        { role: "user", content: [{ type: "input_text", text: "unrelated latest input" }] },
        { role: "developer", content: [{ type: "input_text", text: agentLoopFixtureMarkers.mailbox.idle }] },
        { role: "assistant", content: [{ type: "output_text", text: agentLoopFixtureMarkers.mailbox.active }] },
      ],
    );
    await fixture.waitForMailboxRequest(5_000);
    const snapshot = fixture.snapshot();
    assert.equal(snapshot.failure, null);
    assert.deepEqual(snapshot.requestRoutingFacts[0].mailbox, {
      user: { latestUser: false, anyUser: true, system: false, nonUser: false },
      idle: { latestUser: false, anyUser: false, system: true, nonUser: false },
      active: { latestUser: false, anyUser: false, system: false, nonUser: true },
    });
    assert.doesNotMatch(JSON.stringify(snapshot), /JA_MAILBOX_ACCEPTANCE/u);
    fixture.releaseMailboxResponse();
    const response = await request;
    assert.equal(response.status, 200);
    assert.match(await response.text(), new RegExp(agentLoopFixtureMarkers.mailbox.callId, "u"));
  } finally {
    await fixture.close();
  }
}

test("fixture mailbox routes by a historical user marker and records marker locations", mailboxHistoryMarkerOpensGate);

test("file search fixture requires one four-call batch and all returned results", async () => {
  const fixture = await startFileSearchFixture();
  try {
    const first = await postFixture(fixture, [
      { role: "user", content: [{ type: "input_text", text: fileSearchFixtureMarkers.user }] },
    ]);
    const firstBody = await first.text();
    assert.equal(first.status, 200);
    for (const call of fileSearchFixtureMarkers.calls)
      assert.match(firstBody, new RegExp(call.callId, "u"));

    const outputByCallId = Object.fromEntries([
      [fileSearchFixtureMarkers.calls[0].callId, "README.md\nAGENTS.md\n.codegraph\tdirectory"],
      [fileSearchFixtureMarkers.calls[1].callId, "README.md\nresult"],
      [fileSearchFixtureMarkers.calls[2].callId, "AGENTS.md\nresult"],
      [fileSearchFixtureMarkers.calls[3].callId, ".codegraph\nresult"],
    ]);
    const parsed = parseFileSearchOutputs({
      input: continuation(outputByCallId),
    });
    assert.deepEqual(assertFileSearchOutputs(parsed), {
      callCount: 4,
      outputCharacters: [...parsed.values()].reduce((total, value) => total + value.length, 0),
    });
    const second = await postFixture(fixture, continuation(outputByCallId));
    assert.equal(second.status, 200);
    assert.match(await second.text(), new RegExp(fileSearchFixtureMarkers.final, "u"));
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["initial", "continuation"],
    );
    assert.equal(fixture.snapshot().attempts[1].outputCount, 4);
  } finally {
    await fixture.close();
  }
});

test("file search fixture answers automatic title requests without changing search phases", async () => {
  const fixture = await startFileSearchFixture();
  try {
    const title = await postFixture(fixture, [
      {
        role: "user",
        content: [{ type: "input_text", text: "<user_request>fixture</user_request>" }],
      },
      {
        role: "assistant",
        content: [{ type: "output_text", text: "<assistant_reply>fixture</assistant_reply>" }],
      },
    ]);
    assert.equal(title.status, 200);
    assert.match(await title.text(), /文件搜索验收/u);
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["title"],
    );
  } finally {
    await fixture.close();
  }
});

/** 夹具必须等待单一澄清 Tool 结果，且仅在答案进入续答请求后给最终答复。 */
test("Agent Loop fixture requires the clarification answer before resuming", async () => {
  const fixture = await startFileSearchFixture();
  const markers = agentLoopFixtureMarkers.clarification;
  try {
    const first = await postFixture(
      fixture,
      mixedFileSearchHistory(markers.user),
      [{ name: "request_user_input" }],
    );
    const firstBody = await first.text();
    assert.equal(first.status, 200);
    assert.match(firstBody, new RegExp(markers.callId, "u"));

    const continuation = await postFixture(
      fixture,
      [
        ...mixedFileSearchHistory(markers.user),
        {
          type: "function_call",
          call_id: markers.callId,
          name: "request_user_input",
          arguments: JSON.stringify({ questionId: "question_color" }),
        },
        { type: "function_call_output", call_id: markers.callId, output: markers.answer },
      ],
      [{ name: "request_user_input" }],
    );
    assert.equal(continuation.status, 200);
    assert.match(await continuation.text(), new RegExp(markers.final, "u"));
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["clarification_request", "clarification_resumption"],
    );
    assert.equal(fixture.snapshot().attempts[1].answerSeen, true);
    assert.equal(fixture.snapshot().attempts[1].outputCount, 1);

    const missingAnswer = await postFixture(
      fixture,
      [
        ...mixedFileSearchHistory(markers.user),
        { type: "function_call_output", call_id: markers.callId, output: "answer omitted" },
      ],
      [{ name: "request_user_input" }],
    );
    assert.equal(missingAnswer.status, 400);
  } finally {
    await fixture.close();
  }
});

test("Agent Loop approval routes by the current user turn despite old search results", async () => {
  const fixture = await startFileSearchFixture();
  const markers = agentLoopFixtureMarkers.approval;
  try {
    const first = await postFixture(
      fixture,
      mixedFileSearchHistory(markers.user),
      [{ name: "exec_command" }],
    );
    assert.equal(first.status, 200);
    const stream = await first.text();
    assert.match(stream, new RegExp(markers.callId, "u"));
    const completed = stream
      .split("\n")
      .filter((line) => line.startsWith("data: "))
      .map((line) => JSON.parse(line.slice(6)))
      .find((event) => event.type === "response.completed");
    const invocation = completed.response.output.find((item) => item.type === "function_call");
    assert.equal(invocation.name, "exec_command");
    assert.deepEqual(JSON.parse(invocation.arguments), { cmd: sleepCommand(process.platform) });

    const continuationResponse = await postFixture(
      fixture,
      [
        ...mixedFileSearchHistory(markers.user),
        {
          type: "function_call",
          call_id: markers.callId,
          name: "exec_command",
          arguments: JSON.stringify({ command: markers.command }),
        },
        { type: "function_call_output", call_id: markers.callId, output: "Shell completed" },
      ],
      [{ name: "exec_command" }],
    );
    assert.equal(continuationResponse.status, 200);
    assert.match(await continuationResponse.text(), /Shell tool returned/u);
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["approval_request", "approval_continuation"],
    );
    assert.equal(fixture.snapshot().attempts[1].outputCount, 1);
  } finally {
    await fixture.close();
  }
});

/** Mailbox fixture 在 Provider 请求挂起期间等待 runner 投递，再把消息送入下一次请求。 */
test("Agent Loop fixture gates a running Provider request until the mailbox message is queued", async () => {
  const fixture = await startFileSearchFixture();
  const markers = agentLoopFixtureMarkers.mailbox;
  let responsePending = false;
  let initial;
  try {
    initial = postFixture(
      fixture,
      [
        ...mixedFileSearchHistory(markers.user),
        { role: "user", content: [{ type: "input_text", text: markers.idle }] },
      ],
      [{ name: "ls" }],
    );
    responsePending = true;
    await fixture.waitForMailboxRequest(2_000);
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["mailbox_initial"],
    );
    fixture.releaseMailboxResponse();
    responsePending = false;
    const first = await initial;
    assert.equal(first.status, 200);
    assert.match(await first.text(), new RegExp(markers.callId, "u"));

    const next = await postFixture(
      fixture,
      [
        ...mixedFileSearchHistory(markers.user),
        { role: "user", content: [{ type: "input_text", text: markers.idle }] },
        {
          type: "function_call",
          call_id: markers.callId,
          name: "ls",
          arguments: JSON.stringify({ path: "." }),
        },
        { type: "function_call_output", call_id: markers.callId, output: "README.md" },
        {
          role: "user",
          content: [{ type: "input_text", text: `${markers.user} ${markers.active}` }],
        },
      ],
      [{ name: "ls" }],
    );
    assert.equal(next.status, 200);
    assert.match(await next.text(), new RegExp(markers.final, "u"));
    assert.deepEqual(
      fixture.snapshot().attempts.map((attempt) => attempt.kind),
      ["mailbox_initial", "mailbox_next_request"],
    );
    assert.equal(fixture.snapshot().attempts[1].activeMessageSeen, true);
  } finally {
    if (responsePending) fixture.releaseMailboxResponse();
    await initial?.catch(() => undefined);
    await fixture.close();
  }
});

test("file search runner rejects unbounded stress size and requires four successful durations", () => {
  assert.throws(() => parseArguments(["--stress-files", "999"]), /between 1000 and 20000/u);
  assert.equal(parseArguments(["--stress-files", "1000"]).stressFiles, 1000);
  assert.equal(parseArguments([]).keepFailedProfile, false);
  assert.equal(
    parseArguments(["--evidence-directory", "evidence", "--keep-failed-profile"])
      .keepFailedProfile,
    true,
  );
  assert.throws(
    () => parseArguments(["--keep-failed-profile"]),
    /requires --evidence-directory/u,
  );
  const native = parseArguments(["--executable", "fixture-app-server.exe"]);
  assert.equal(native.executable, "fixture-app-server.exe");
  assert.equal(native.jar, undefined);
  assert.equal(parseArguments(["--strip-search-tools"]).stripSearchTools, true);
  assert.throws(
    () =>
      parseArguments(["--jar", "fixture-app-server.jar", "--executable", "fixture-app-server.exe"]),
    /mutually exclusive/u,
  );
  assert.throws(() =>
    validateFileSearchReport({
      schemaVersion: 1,
      status: "passed",
      provider: { kind: "deterministic_loopback", externalCalls: 0 },
      tools: { resultCount: 3, allResultsReturned: false, calls: [] },
      persistence: {},
      durations: { wallDurationMs: 0 },
    }),
  );

  const validReport = {
    schemaVersion: 1,
    status: "passed",
    runtime: { launcher: "native", javaMajor: null },
    environment: {
      searchToolPathStripped: true,
      removedSearchToolPathEntries: 1,
      searchToolsAbsentFromChildPath: true,
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0 },
    tools: {
      resultCount: 4,
      allResultsReturned: true,
      calls: Array.from({ length: 4 }, () => ({
        outcome: "succeeded",
        status: "success",
        durationMs: 1,
      })),
    },
    persistence: {},
    restart: {
      mainThreadHistoryReadable: true,
      clarificationHistoryReadable: true,
      approvalHistoryReadable: true,
      mailboxThreadPurged: true,
      mailboxTaskPurged: true,
    },
    agentLoop: {
      clarification: { answered: true, resumed: true },
      approvalCancellation: {
        approved: true,
        shellStarted: true,
        cancelAccepted: true,
        terminalCancelled: true,
      },
      mailbox: {
        idleMessageDidNotWake: true,
        runningMessageReachedNextRequest: true,
        messagesConsumed: true,
      },
    },
    durations: { wallDurationMs: 1 },
  };
  assert.equal(validateFileSearchReport(validReport), validReport);
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      environment: { ...validReport.environment, searchToolsAbsentFromChildPath: false },
    }),
  );
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      environment: {
        searchToolPathStripped: false,
        removedSearchToolPathEntries: 0,
        searchToolsAbsentFromChildPath: true,
      },
    }),
  );
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      agentLoop: {
        ...validReport.agentLoop,
        mailbox: { ...validReport.agentLoop.mailbox, idleMessageDidNotWake: false },
      },
    }),
  );
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      restart: { ...validReport.restart, mailboxThreadPurged: false },
    }),
  );
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      restart: { ...validReport.restart, mailboxTaskPurged: false },
    }),
  );
});

/** 保留开关只适用于失败排障；成功或默认失败仍删除临时 profile。 */
test("failed profile retention requires explicit opt-in and never affects success cleanup", async () => {
  const cases = [
    { failed: true, keepFailedProfile: true, retained: true },
    { failed: true, keepFailedProfile: false, retained: false },
    { failed: false, keepFailedProfile: true, retained: false },
  ];
  for (const scenario of cases) {
    const directories = await createProviderSmokeDirectories();
    const root = directories.root;
    try {
      assert.equal(
        await cleanupAcceptanceProfile(root, scenario),
        scenario.retained,
      );
      if (scenario.retained) {
        assert.ok((await stat(root)).isDirectory());
      } else {
        await assert.rejects(stat(root), { code: "ENOENT" });
      }
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  }
});

/** 物理 temp parent 让 macOS 系统目录别名不穿透生产目录边界校验。 */
test("isolated acceptance roots use the physical temporary parent", async () => {
  const directories = await createProviderSmokeDirectories();
  try {
    assert.equal(dirname(directories.root), await realpath(tmpdir()));
  } finally {
    await rm(directories.root, { recursive: true, force: false });
  }
});

/** 仓库路径取自当前检出，搬迁目录后仍验证真实路径脱敏，而非固定开发机路径。 */
test("file search failure evidence is redacted and bounded", () => {
  const repositoryPath = fileURLToPath(new URL("../../", import.meta.url)).replaceAll("\\", "/");
  const evidence = buildFileSearchFailureEvidence({
    error: new Error("terminal failed for token=mock-value in C:\\private\\profile"),
    phase: "validating_final_reply",
    session: {
      events: Array.from({ length: 140 }, (_, index) => ({
        method: "turn/event",
        params: {
          index,
          apiKey: "mock-value",
          cwd: "C:\\private\\profile",
          text: `frame-${index} at http://127.0.0.1:3333`,
        },
      })),
      stderr: `Authorization: Bearer mock-value C:\\private\\profile file:/${repositoryPath}ja-agent/ja-app-server/target/ja-app-server.jar`,
    },
    fixtureSnapshot: {
      attempts: [{ kind: "continuation", toolNames: ["find"], callIds: ["call-1"] }],
      failure: { category: "provider", message: "credential=mock-value" },
    },
    directories: { root: "C:\\private\\profile" },
    fixtureEndpoint: "http://127.0.0.1:3333",
    launcher: "jvm",
    javaMajor: 25,
    preservedProfilePath: "C:\\isolated\\ja-real-provider-abc",
  });

  const serialized = JSON.stringify(evidence);
  assert.equal(evidence.phase, "validating_final_reply");
  assert.equal(evidence.events.frameCount, 140);
  assert.equal(evidence.events.truncated, true);
  assert.ok(evidence.events.frames.length <= 128);
  assert.equal(evidence.retainedFailureProfile.path, "C:\\isolated\\ja-real-provider-abc");
  assert.ok(serialized.includes("<redacted>"));
  assert.ok(serialized.includes("<isolated-path>"));
  assert.ok(serialized.includes("<fixture-endpoint>"));
  assert.ok(!serialized.includes("mock-value"));
  assert.ok(!serialized.includes("C:\\\\private"));
  assert.ok(!serialized.includes("127.0.0.1:3333"));
  assert.ok(!serialized.includes(repositoryPath));
});
