// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  conversationProgressFixtureMarkers,
  startConversationProgressFixture,
} from "./fixtures/conversation-progress.mjs";
import {
  assertLiveStreamEvidence,
  installConversationProgressInvokeProbe,
  parseArguments,
  readResponseAnimationReplayCount,
  validateConversationProgressReport,
} from "./conversation-progress-webview2.mjs";

/** 以完整顺序基线驱动报告合同测试，防止真窗断言退化成单一布尔状态。 */
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
    runtimeInvocationProbe: {
      boundary: "e2e_nativeInvoke_delegate",
      probeInstalled: true,
      observedCommand: "ja_runtime_state",
      startObserved: true,
      resolvedObserved: true,
      runtimeStatus: "ready",
      publicTimelineStoreSingleton: true,
    },
    identity: {
      git: { head: "0123456789abcdef0123456789abcdef01234567", dirty: true, dirtyEntryCount: 1 },
      sources: {
        controller: { path: "controller", size: 1, sha256: "a".repeat(64) },
        interaction: { path: "interaction", size: 1, sha256: "a".repeat(64) },
        timelineStore: { path: "timelineStore", size: 1, sha256: "a".repeat(64) },
        timelineReducer: { path: "timelineReducer", size: 1, sha256: "a".repeat(64) },
        timelineContracts: { path: "timelineContracts", size: 1, sha256: "a".repeat(64) },
        historyApi: { path: "historyApi", size: 1, sha256: "a".repeat(64) },
        chatTimeline: {
          path: "apps/desktop/src/features/conversation/ui/timeline/ChatTimeline.tsx",
          size: 1,
          sha256: "a".repeat(64),
        },
        workProcess: {
          path: "apps/desktop/src/features/conversation/ui/timeline/WorkProcess.tsx",
          size: 1,
          sha256: "a".repeat(64),
        },
        activeStreamRegistry: {
          path: "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/runtime/ActiveStreamRegistry.java",
          size: 1,
          sha256: "a".repeat(64),
        },
        rpcSession: { path: "rpcSession", size: 1, sha256: "a".repeat(64) },
        threadHistoryHandler: { path: "threadHistoryHandler", size: 1, sha256: "a".repeat(64) },
        rpcResults: { path: "rpcResults", size: 1, sha256: "a".repeat(64) },
        threadReadContract: { path: "threadReadContract", size: 1, sha256: "a".repeat(64) },
        rustHistoryModel: { path: "rustHistoryModel", size: 1, sha256: "a".repeat(64) },
        mybatisHistoryService: {
          path: "app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/repository/MybatisHistoryService.java",
          size: 1,
          sha256: "a".repeat(64),
        },
        agentLoopPersistence: {
          path: "app-server/src/main/java/io/github/kongweiguang/ja/conversation/application/loop/AgentLoopPersistence.java",
          size: 1,
          sha256: "a".repeat(64),
        },
        turnEventSink: {
          path: "app-server/src/main/java/io/github/kongweiguang/ja/conversation/port/in/TurnEventSink.java",
          size: 1,
          sha256: "a".repeat(64),
        },
        threadSnapshot: {
          path: "app-server/src/main/java/io/github/kongweiguang/ja/conversation/domain/ThreadSnapshot.java",
          size: 1,
          sha256: "a".repeat(64),
        },
      },
      jar: { path: "ja-app-server.jar", size: 1, sha256: "b".repeat(64) },
      jaExecutable: { status: "unavailable", reason: "test" },
      window: {
        title: "Ja",
        url: "http://localhost:5173/",
        viewport: { width: 1280, height: 800, devicePixelRatio: 1 },
        userAgent: "WebView2",
        webViewVersion: "140.0.0.0",
      },
      consoleErrors: [],
      pageErrors: [],
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0, toolCalls: 2 },
    live: {
      progressInsideProcessBeforeFirstTool: true,
      workingStatusWithProcess: true,
      finalDraftInsideProcessBeforeTerminal: true,
      finalBodyOutsideProcess: true,
      processNodeStable: true,
      responseNodeStable: true,
      historyRunningStatusNodeStable: true,
      animationReplays: { response: 0, history: 0 },
      historyLoadingIndicatorMounts: 0,
      terminalCalibratedExistingResponse: true,
      completedProcessCollapsed: true,
      readSummaryVisible: true,
      sequence: ["commentary", "tool:read", "commentary", "commentary", "tool:shell", "commentary"],
      noDuplicateTools: true,
      flicker: {
        resyncReadCount: 1,
        threadReadCount: 3,
        liveStreamBaseline: { turnId: "turn-test", streamSeq: 2, segmentCount: 1 },
        terminalReadCountBeforeQuiet: 2,
        terminalReadCountAfterQuiet: 2,
        responseTextRegressions: 0,
        progressTextRegressions: 0,
        backgroundReadWindow: {
          started: true,
          ended: true,
          newConversationDisabledTransitions: 0,
          composerDisabledTransitions: 0,
          newConversationStates: [false],
          composerStates: [false],
        },
        disabledTransitions: { newConversation: 0, composer: 0 },
        spinnerStartTimes: { history: [100], response: [200] },
      },
    },
    reload: {
      sameThread: true,
      readSummaryVisible: true,
      finalBodyOutsideProcess: true,
      sequence: ["commentary", "tool:read", "commentary", "commentary", "tool:shell", "commentary"],
    },
    failureContinuation: {
      failedTerminalVisible: true,
      continueActionVisible: true,
      newTurnCompleted: true,
      oldToolsReplayed: false,
      continuationAnimationStart: 300,
      animationReplaysBeforeContinue: 0,
      animationReplaysAfterContinue: 1,
      terminalReadCountBeforeQuiet: 4,
      terminalReadCountAfterQuiet: 4,
      workIndicatorStopped: true,
      screenshots: ["conversation-progress-failure.png", "conversation-progress-continued.png"],
      readCountBeforeContinue: 3,
    },
    contextUsage: {
      // 固定 279px 覆盖 WebView2 非整数 DPI 的真实回报，防止报告合同重新退化为精确像素比较。
      width: 279,
      userSelect: "text",
      metrics: {
        newInput: "60",
        output: "36",
        cacheRead: "0",
        total: "96",
        cacheRate: "0.0%",
        context: "0.0%",
        used: "20 / 128k",
      },
      narrow: { documentOverflows: false },
    },
    finalVisible: true,
  };
}

/** 等待 fixture 到达可释放阶段，确保测试不靠任意延迟并能在故障时有界失败。 */
async function waitForFixtureStage(fixture, expectedStage) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    if (fixture.stages.includes(expectedStage)) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
  }
  throw new Error(`fixture stage ${expectedStage} was not reached before the deadline`);
}

test("CLI 默认使用 JDK25 与独立 conversation-progress Cargo target", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    "C:\\Temp\\ja-progress-evidence",
    "--jar",
    "C:\\Temp\\ja-app-server.jar",
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-conversation-progress$/u);
});

/** 证明 runner 使用 E2E nativeInvoke delegate 接缝，而不是不可写的 Tauri 内部 bridge。 */
test("delegate probe 记录真实 adapter command 且保持结果透传", async () => {
  const globalValue = globalThis;
  delete globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__;
  delete globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__;
  try {
    installConversationProgressInvokeProbe();
    const result = await globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__(
      { command: "ja_runtime_state", args: {} },
      async () => ({ status: "ready" }),
    );
    assert.deepEqual(result, { status: "ready" });
    assert.deepEqual(
      globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__.map((entry) => [entry.command, entry.phase]),
      [
        ["ja_runtime_state", "start"],
        ["ja_runtime_state", "received"],
        ["ja_runtime_state", "resolved"],
      ],
    );
  } finally {
    delete globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__;
    delete globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__;
    delete globalValue.__JA_CONVERSATION_THREAD_READ_HOLD__;
    delete globalValue.__JA_CONVERSATION_THREAD_READ_RELEASE__;
  }
});

/** 收到 null liveStream 时必须在 response 边界失败，避免 runner 持有请求直到 Provider 超时。 */
test("thread/read null baseline fails immediately at the received boundary", async () => {
  const globalValue = globalThis;
  delete globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__;
  delete globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__;
  try {
    installConversationProgressInvokeProbe();
    globalValue.__JA_CONVERSATION_THREAD_READ_HOLD__ = true;
    const response = globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__(
      { command: "ja_thread_read", args: {} },
      async () => ({
        threadId: "thread-test",
        revision: 3,
        liveStream: null,
      }),
    );
    await new Promise((resolvePromise) => setImmediate(resolvePromise));
    const received = globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__.find(
      (entry) => entry.phase === "received",
    );
    assert.equal(received?.liveStream, null);
    assert.throws(
      () =>
        assertLiveStreamEvidence({ received: [received] }, "thread-test", received?.invocationId),
      /complete liveStream baseline/u,
    );
    globalValue.__JA_CONVERSATION_THREAD_READ_HOLD__ = false;
    globalValue.__JA_CONVERSATION_THREAD_READ_RELEASE__();
    await response;
  } finally {
    delete globalValue.__JA_E2E_NATIVE_INVOKE_PROBE__;
    delete globalValue.__JA_CONVERSATION_PROGRESS_INVOKES__;
    delete globalValue.__JA_CONVERSATION_THREAD_READ_HOLD__;
    delete globalValue.__JA_CONVERSATION_THREAD_READ_RELEASE__;
  }
});

/** reload 后动画 evidence 缺失必须失败，不能由 fallback 零值掩盖 listener 未重装。 */
test("animation replay count requires an installed probe", async () => {
  const globalValue = globalThis;
  const page = { evaluate: async (callback) => callback() };
  delete globalValue.__JA_CONVERSATION_FLICKER_EVIDENCE__;
  await assert.rejects(
    () => readResponseAnimationReplayCount(page),
    /animation evidence is unavailable/u,
  );
  globalValue.__JA_CONVERSATION_FLICKER_EVIDENCE__ = { animationReplays: { response: 1 } };
  await assert.doesNotReject(async () => {
    assert.equal(await readResponseAnimationReplayCount(page), 1);
  });
  delete globalValue.__JA_CONVERSATION_FLICKER_EVIDENCE__;
});

test("报告必须证明流式正文留在工作过程、terminal 收口与 reload 后的公开顺序", () => {
  const report = validReport();
  assert.equal(validateConversationProgressReport(report), report);
  report.contextUsage.metrics.cacheRate = "—";
  assert.throws(() => validateConversationProgressReport(report), /deep-equal|equal/u);
  report.contextUsage.metrics.cacheRate = "0.0%";
  report.identity.sources.activeStreamRegistry.path =
    "app-server/src/main/java/io/github/kongweiguang/ja/conversation/application/stream/ActiveStreamRegistry.java";
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
  report.identity.sources.activeStreamRegistry.path =
    "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/runtime/ActiveStreamRegistry.java";
  report.live.processNodeStable = false;
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
  report.live.processNodeStable = true;
  report.runtimeInvocationProbe.probeInstalled = false;
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
  report.runtimeInvocationProbe.probeInstalled = true;
  report.live.historyLoadingIndicatorMounts = 1;
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
  report.live.historyLoadingIndicatorMounts = 0;
  report.reload.sequence = ["commentary", "tool:read", "tool:shell", "commentary"];
  assert.throws(() => validateConversationProgressReport(report), /interleave|deep-equal|equal/u);
  report.reload.sequence = [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ];
  report.live.flicker.liveStreamBaseline.streamSeq = 0;
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
  report.live.flicker.liveStreamBaseline.streamSeq = 2;
  report.failureContinuation.oldToolsReplayed = true;
  assert.throws(() => validateConversationProgressReport(report), /equal/u);
});

/** 当前 Agent 提示的公开进展约束必须被 loopback fixture 识别，防止真窗只因验收关键字过时而失败。 */
test("loopback fixture 按 function_call_output 推进 read、shell、final 三轮", async () => {
  const fixture = await startConversationProgressFixture();
  try {
    const instructions =
      "Before the first tool call, briefly explain your intent. " +
      "Share meaningful findings and changes of direction without narrating every operation.";
    const post = (input) =>
      fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ input, instructions }),
      });
    const firstResponse = post([
      { role: "user", content: [{ type: "input_text", text: "progress" }] },
    ]);
    await waitForFixtureStage(fixture, "text_read");
    fixture.releaseFirstText();
    const first = await (await firstResponse).text();
    assert.match(first, /response\.output_text\.delta/u);
    assert.doesNotMatch(first, /response\.reasoning_summary_text\.delta/u);
    assert.match(first, /call_progress_read/u);
    const secondResponse = post([
      { type: "function_call", call_id: "call_progress_read", name: "read", arguments: "{}" },
      { type: "function_call_output", call_id: "call_progress_read", output: "no head" },
    ]);
    await waitForFixtureStage(fixture, "summary_shell");
    fixture.releaseSecondNarrative();
    const second = await (await secondResponse).text();
    assert.match(second, /call_progress_shell/u);
    const thirdResponse = post([
      { type: "function_call", call_id: "call_progress_shell", name: "shell", arguments: "{}" },
      {
        type: "function_call_output",
        call_id: "call_progress_shell",
        output: "JA_PROGRESS_SHELL_OK",
      },
    ]);
    await waitForFixtureStage(fixture, "summary_final");
    fixture.releaseFinalNarrative();
    await waitForFixtureStage(fixture, "text_final");
    fixture.releaseFinalText();
    const third = await (await thirdResponse).text();
    assert.match(third, new RegExp(conversationProgressFixtureMarkers.final, "u"));
    assert.deepEqual(
      fixture
        .snapshot()
        .attempts.filter((attempt) => attempt.kind === "turn")
        .map((attempt) => attempt.step),
      [0, 1, 2],
    );
    assert.equal(
      fixture
        .snapshot()
        .attempts.filter((attempt) => attempt.kind === "turn")
        .every((attempt) => attempt.progressInstruction),
      true,
    );
  } finally {
    await fixture.close();
  }
});

/** 独立验证失败终态只允许新的“继续”请求恢复，不会让 fixture 把旧 Tool 当作新一轮重放。 */
test("loopback fixture 支持失败终态后独立继续 Turn", async () => {
  const fixture = await startConversationProgressFixture();
  try {
    const instructions =
      "Before the first tool call, briefly explain your intent. " +
      "Share meaningful findings and changes of direction without narrating every operation.";
    const post = (input) =>
      fetch(`${fixture.baseUrl}/responses`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ input, instructions }),
      });
    const first = post([{ role: "user", content: [{ type: "input_text", text: "progress" }] }]);
    await waitForFixtureStage(fixture, "text_read");
    fixture.releaseFirstText();
    await (await first).text();
    const second = post([
      { type: "function_call", call_id: "call_progress_read", name: "read", arguments: "{}" },
      { type: "function_call_output", call_id: "call_progress_read", output: "no head" },
    ]);
    await waitForFixtureStage(fixture, "summary_shell");
    fixture.releaseSecondNarrative();
    await (await second).text();
    const third = post([
      { type: "function_call", call_id: "call_progress_shell", name: "shell", arguments: "{}" },
      {
        type: "function_call_output",
        call_id: "call_progress_shell",
        output: "JA_PROGRESS_SHELL_OK",
      },
    ]);
    await waitForFixtureStage(fixture, "summary_final");
    fixture.releaseFinalNarrative();
    await waitForFixtureStage(fixture, "text_final");
    fixture.releaseFinalText();
    await (await third).text();

    const failed = post([{ role: "user", content: [{ type: "input_text", text: "触发失败" }] }]);
    await waitForFixtureStage(fixture, "failure_terminal");
    const failedResponse = await failed;
    assert.equal(failedResponse.status, 400);

    const continued = post([{ role: "user", content: [{ type: "input_text", text: "继续" }] }]);
    await waitForFixtureStage(fixture, "summary_continue");
    fixture.releaseContinuationNarrative();
    await waitForFixtureStage(fixture, "text_continue");
    fixture.releaseContinuationText();
    const continuedResponse = await continued;
    assert.equal(continuedResponse.status, 200);
    assert.match(
      await continuedResponse.text(),
      new RegExp(conversationProgressFixtureMarkers.continueFinal, "u"),
    );
    assert.deepEqual(
      fixture
        .snapshot()
        .attempts.filter((attempt) => attempt.kind === "turn")
        .map((attempt) => [attempt.step, attempt.outcome ?? "completed"]),
      [
        [0, "completed"],
        [1, "completed"],
        [2, "completed"],
        [3, "failed"],
        [4, "completed"],
      ],
    );
  } finally {
    await fixture.close();
  }
});
