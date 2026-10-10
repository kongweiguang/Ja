// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { resolve } from "node:path";
import {
  collectSourceBuildIdentity,
  parseArguments,
  startProcessSessionProvider,
  validateProcessSessionWebView2Report,
} from "./process-session-webview2.mjs";

/** 默认只指向当前 Maven 构建产物，并用坏路径负例固定证据目录的写入边界。 */
test("parses an owned evidence directory and the shared Cargo target", function testArguments() {
  const evidenceDirectory = resolve(".tmp", "process-session-webview2-test");
  const options = parseArguments(["--evidence-directory", evidenceDirectory]);

  assert.equal(options.evidenceDirectory, evidenceDirectory);
  assert.equal(
    options.jar,
    resolve("ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  assert.equal(options.cargoTargetDirectory, resolve("target", "codex-review-redesign"));
  for (const invalidPath of [
    resolve("..", "outside-evidence"),
    resolve(".tmp"),
    resolve(".tmp-sibling", "outside-evidence"),
    resolve("Z:\\outside-evidence"),
  ]) {
    assert.throws(
      () => parseArguments(["--evidence-directory", invalidPath]),
      /child of repository \.tmp/u,
      `runner must reject ${invalidPath}`,
    );
  }
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        evidenceDirectory,
        "--cargo-target-directory",
        resolve("target", "separate-acceptance-build"),
      ]),
    /shared codex-review-redesign Cargo target/u,
  );
});

/** 对当前 Java/Maven 源输入计算确定性摘要，供 smoke 报告与产物新鲜度核对。 */
test("collects a stable non-empty current App Server source identity", async function testBuildIdentity() {
  const identity = await collectSourceBuildIdentity();

  assert.match(identity.sha256, /^[a-f0-9]{64}$/u);
  assert.ok(Number.isSafeInteger(identity.fileCount) && identity.fileCount > 0);
  assert.ok(Number.isFinite(identity.latestSourceMtimeMs) && identity.latestSourceMtimeMs > 0);
});

/** fixture 必须先发出真实 Responses function_call，再把 ToolResult 对应成终答。 */
/** fixture 的五类场景都必须形成真实 Responses function_call 与对应终答。 */
test("serves deterministic process, EOF, exit-code, approval, and denial turns", async function testProvider(t) {
  const fixture = await startProcessSessionProvider();
  t.after(() => fixture.close());
  const endpoint = `${fixture.baseUrl}/responses`;
  const post = (input) =>
    fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ input }),
    });
  const scenarios = [
    {
      name: "process",
      marker: "JA_PROCESS_SESSION_WEBVIEW2_START",
      callId: "call_process_session_webview2_start",
      finalMarker: "PROCESS_SESSION_WEBVIEW2_FINAL_OK",
    },
    {
      name: "eof",
      marker: "JA_PROCESS_SESSION_WEBVIEW2_EOF",
      callId: "call_process_session_webview2_eof",
      finalMarker: "PROCESS_SESSION_WEBVIEW2_EOF_FINAL_OK",
    },
    {
      name: "exit_23",
      marker: "JA_PROCESS_SESSION_WEBVIEW2_EXIT_23",
      callId: "call_process_session_webview2_exit_23",
      finalMarker: "PROCESS_SESSION_WEBVIEW2_EXIT_23_FINAL_OK",
    },
    {
      name: "approval",
      marker: "JA_PROCESS_SESSION_WEBVIEW2_APPROVAL",
      callId: "call_process_session_webview2_approval",
      finalMarker: "PROCESS_SESSION_WEBVIEW2_APPROVAL_FINAL_OK",
    },
    {
      name: "denied",
      marker: "JA_PROCESS_SESSION_WEBVIEW2_DENIED",
      callId: "call_process_session_webview2_denied",
      finalMarker: "PROCESS_SESSION_WEBVIEW2_DENIED_FINAL_OK",
    },
  ];
  for (const scenario of scenarios) {
    const userInput = {
      role: "user",
      content: [{ type: "input_text", text: `Run ${scenario.name} ${scenario.marker}` }],
    };
    const toolResponse = await post([userInput]);
    assert.equal(toolResponse.status, 200);
    const toolBody = await toolResponse.text();
    assert.match(toolBody, new RegExp(scenario.callId, "u"));
    assert.match(toolBody, /exec_command/u);

    const finalResponse = await post([
      userInput,
      {
        type: "function_call_output",
        call_id: scenario.callId,
        output: `completed ${scenario.name}`,
      },
    ]);
    assert.equal(finalResponse.status, 200);
    assert.match(await finalResponse.text(), new RegExp(scenario.finalMarker, "u"));
  }
  assert.deepEqual(
    fixture.observations,
    scenarios.flatMap(({ name }) => [
      { scenario: name, phase: "tool" },
      { scenario: name, phase: "final" },
    ]),
  );
  assert.equal(fixture.failure(), undefined);
});

/** 报告校验要求观察到真实进程、审批结果和完整会话导出。 */
test("validates passed WebView2 evidence and rejects partial export", function testReports() {
  const report = {
    contractVersion: 1,
    status: "passed",
    runtimeBoundary: "windows_tauri_webview2_debug_jar",
    threadId: "thr_process_session_webview2",
    process: {
      turnCompletedBeforeInput: true,
      firstTurnEvidence: {
        thread: {
          threadId: "thr_process_session_webview2",
          toolCalls: [
            {
              turnId: "turn_process_session_webview2",
              callId: "call_process_session_webview2_start",
              toolName: "exec_command",
              presentation: { kind: "shell", status: "success" },
            },
          ],
        },
        process: {
          workspaceId: "ws_project",
          threadId: "thr_process_session_webview2",
          generation: 3,
          sessions: [
            {
              workspaceId: "ws_project",
              threadId: "thr_process_session_webview2",
              generation: 3,
              startedTurnId: "turn_process_session_webview2",
              startedCallId: "call_process_session_webview2_start",
              sessionId: "proc_0123456789abcdef0123456789abcdef",
              state: "running",
            },
          ],
          snapshot: {
            workspaceId: "ws_project",
            threadId: "thr_process_session_webview2",
            generation: 3,
            startedCallId: "call_process_session_webview2_start",
            sessionId: "proc_0123456789abcdef0123456789abcdef",
            state: "running",
            output: "JA_PROCESS_SESSION_READY",
          },
        },
      },
      initialOutputVisible: true,
      inputProducedIncrementalOutput: true,
      stopReachedExitState: true,
      exitCode: 137,
      focusAfterStop: { label: "命令输出" },
      keyboardFocusRecoveredAfterStop: true,
      stdinEof: {
        emptyInputSubmitted: true,
        eofObserved: true,
        exitCode: 0,
        focusReturnedToOutput: true,
      },
      naturalExit: {
        outputMarkerVisible: true,
        exitCode: 23,
        inputControlsRemoved: true,
      },
    },
    permissions: {
      labels: ["需要审批", "完全访问"],
      fullAccessStartedProcess: true,
      approvalRequiredToolWasApproved: true,
      approvedToolProducedExpectedOutput: true,
      approvedDecisionFocusReturned: true,
      approvalRequiredToolWasDenied: true,
      deniedToolStatus: "error",
      deniedCommandDidNotRun: true,
      deniedDecisionFocusReturned: true,
    },
    export: {
      status: "verified",
      pickerBoundary: "cdp_test_file_handle",
      nativePickerSelectionVerified: false,
      successFeedback: true,
      markdownContainsAllAcceptanceTurns: true,
      messageSections: 10,
      size: 128,
      sha256: "a".repeat(64),
    },
    pageErrors: [],
    consoleErrors: [],
    errorOverlays: [],
  };
  assert.equal(validateProcessSessionWebView2Report(report), report);

  const missingProcess = structuredClone(report);
  missingProcess.process.firstTurnEvidence.process.sessions = [];
  assert.throws(() => validateProcessSessionWebView2Report(missingProcess), {
    operator: "strictEqual",
  });

  const mismatchedSession = structuredClone(report);
  mismatchedSession.process.firstTurnEvidence.process.snapshot.sessionId =
    "proc_ffffffffffffffffffffffffffffffff";
  assert.throws(() => validateProcessSessionWebView2Report(mismatchedSession), {
    operator: "strictEqual",
  });

  const partial = structuredClone(report);
  partial.status = "partial";
  partial.export = { status: "not_verified", menuActionVisible: true };
  assert.throws(() => validateProcessSessionWebView2Report(partial), {
    operator: "strictEqual",
  });
  assert.throws(
    () =>
      validateProcessSessionWebView2Report({
        ...report,
        process: { ...report.process, stopReachedExitState: false },
      }),
    { operator: "strictEqual" },
  );
  const missingEof = structuredClone(report);
  missingEof.process.stdinEof.eofObserved = false;
  assert.throws(() => validateProcessSessionWebView2Report(missingEof), {
    operator: "strictEqual",
  });
  const wrongNaturalExit = structuredClone(report);
  wrongNaturalExit.process.naturalExit.exitCode = 0;
  assert.throws(() => validateProcessSessionWebView2Report(wrongNaturalExit), {
    operator: "strictEqual",
  });
  const successfulDeniedCommand = structuredClone(report);
  successfulDeniedCommand.permissions.deniedToolStatus = "success";
  assert.throws(() => validateProcessSessionWebView2Report(successfulDeniedCommand), {
    operator: "strictEqual",
  });
  const missingDecisionFocus = structuredClone(report);
  missingDecisionFocus.permissions.deniedDecisionFocusReturned = false;
  assert.throws(() => validateProcessSessionWebView2Report(missingDecisionFocus), {
    operator: "strictEqual",
  });

  const incompleteExport = structuredClone(report);
  incompleteExport.export.messageSections = 3;
  assert.throws(() => validateProcessSessionWebView2Report(incompleteExport), {
    operator: "strictEqual",
  });
});
