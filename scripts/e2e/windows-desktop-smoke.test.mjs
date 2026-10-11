// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { Buffer } from "node:buffer";
import { execFileSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, win32 } from "node:path";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { JSDOM } from "jsdom";
import {
  assertOuterTerminalCapabilityContract,
  terminalCloseEvidence,
  injectNextTerminalCloseFailure,
  terminalPaneContainsOutputMarker,
  waitForTerminalPaneOutputMarker,
  captureTerminalPartialCloseDiagnostics,
  installTauriInvokeProbeInPage,
  captureRuntimeBindingInvokeTrace,
  selectThreadById,
  installTaskThreadsExitProbe,
  nativeReviewEvidenceInPage,
  nativeReviewEvidenceReady,
  captureNativeReviewEvidence,
  attachPageDiagnostics,
  captureAriaHiddenFocusSnapshot,
  projectFailureOwnerPresence,
  projectWrapperLifecycle,
  waitForReviewDiff,
  waitForAuthoritativePreviewState,
  exercisePreviewDirectoryRejection,
  openReviewPortalMenu,
  bindOwnedJaWindowHandle,
  assertOwnedJaWindowBinding,
  readOwnedJaWindowPostcheck,
  ConditionTimeoutError,
  runNativeShortcutEventAttempts,
  assertMaximizedWindowEvidence,
  attachMaximizedWindowEvidence,
  assertNativeViewportGeometry,
  combineProfilePrimeFailures,
  windowsPathMatches,
  writeRunSummary,
  assertRuntimeIsolation,
  assertRuntimeRestartEvidence,
  assertRuntimePhaseHandoffEvidence,
  assertRuntimeHandoffBoundary,
  createRuntimeFreezeHandoff,
  classifyProcessIdentityPresence,
  composerContextProjection,
  captureFailureVisualEvidence,
  currentThreadWorkbenchHost,
  currentThreadWorkbenchInspector,
  waitForWorkbenchTabClosed,
  waitForStableTerminalPaneWidth,
  deriveTerminalPaneRatioFromPointerEvidence,
  terminalWorkspaceFailureSummary,
  workbenchCloseFocusEvidenceInPage,
  waitForWorkbenchCloseFocus,
  assertWorkbenchPreferenceEvidence,
  readThreadWorkbenchState,
  waitForJoinedInspectorLayoutSnapshot,
  assertStaticContracts,
  awaitClipboardImportOutcome,
  runtimeRefreshParseAuditedText,
  assertTaskThreadsContract,
  assertTaskThreadsRestartSelectionContract,
  assertTaskThreadsNativeCloseEvidence,
  assertTaskThreadsSharedRuntimeIdentity,
  assertTaskThreadsExitControllerContract,
  assertTaskThreadsDeliveryEvidence,
  assertTaskThreadsAcceptanceEvidence,
  assertTaskThreadsClosedTimelineEvidence,
  taskNativeExitEvidenceIsComplete,
  taskTurnAcceptedIdsMatchReducer,
  classifySingleTaskInvokeOutcome,
  waitForTaskInvokeOutcome,
  waitForInteractionPlanRuntimeCondition,
  planFixtureStepStatus,
  nextPlanToolName,
  classifyTaskRefreshBatchOutcome,
  normalizeTaskCancellationAuthorityDiagnostics,
  classifyTaskInvokeQuiescence,
  waitForTaskInvokeQuiescence,
  waitForTaskRefreshOutcomes,
  expectedTaskCancelInvokeDelta,
  taskClosedNotificationDiagnostics,
  taskClosedNotificationMatchesContract,
  taskTreeMetaMatchesAuthority,
  readTaskMetricDefinitionChecks,
  taskMetricDefinitionChecksAreValid,
  readRuntimeEndpointMetadata,
  assertRuntimeReadyBinding,
  assertFrozenRuntimeBinding,
  isFinalRuntimeShutdownPhase,
  requestRunScopedRuntimeShutdown,
  frozenRuntimeIdentities,
  refreshRuntimeIdentityHistory,
  mergeRuntimeIdentityHistory,
  runtimeNaturalExitState,
  runtimeShutdownPhaseAcknowledged,
  coreExecutionCompleted,
  runtimeRefreshExecutionFact,
  runtimeRefreshPersistenceFacts,
  runtimeContextUsageCertainty,
  runtimeContextUsageProjection,
  projectInteractionPlanExecuteAttempt,
  dsmlPersistenceRowCount,
  planExecutionLedgerFact,
  queuedAttachmentFact,
  attachmentOwnershipFact,
  taskThreadMessageFact,
  summarizeTauriEventContractIssues,
} from "./windows-desktop-smoke.mjs";

/** 真窗前的纯源码门禁必须能从 Node 定向测试独立运行。 */
test("static smoke contracts pass without starting the desktop runner", () => {
  assert.doesNotThrow(assertStaticContracts);
});

/** 失败 Tool 不证明请求中的状态已入账，下一轮仍须尝试 READY→RUNNING。 */
test("Plan fixture keeps the durable step status after a failed tool result", () => {
  const context = { kind: "plan", planId: "plan_fixture", runId: "run_fixture" };
  const input = [
    {
      type: "function_call",
      call_id: "call_step",
      name: "plan_step_update",
      arguments: JSON.stringify({ ...context, status: "running" }),
    },
    {
      type: "function_call_output",
      call_id: "call_step",
      status: "completed",
      output: "Workspace guidance changed; review the refreshed context before retrying",
    },
  ];
  assert.equal(planFixtureStepStatus(input, context), "ready");
  assert.equal(nextPlanToolName(input), "plan_step_update");
});

/** committed 回执须匹配调用与聚合；成功 RUNNING 推进，成功 SUCCEEDED 才结束。 */
test("Plan fixture advances only from a matching committed receipt", () => {
  const context = { kind: "plan", planId: "plan_fixture", runId: "run_fixture" };
  for (const status of ["running", "succeeded"]) {
    const input = [
      {
        type: "function_call",
        call_id: "call_step",
        name: "plan_step_update",
        arguments: JSON.stringify({ ...context, status }),
      },
      {
        type: "function_call_output",
        call_id: "call_step",
        output: JSON.stringify({
          aggregateId: context.planId,
          callId: "call_step",
          toolName: "plan_step_update",
          status: "committed",
        }),
      },
    ];
    assert.equal(planFixtureStepStatus(input, context), status);
    assert.equal(nextPlanToolName(input), status === "running" ? "plan_step_update" : undefined);
  }
});

/** 其它调用、其它 Plan、未完成回执和显式错误均不得更改模拟器的当前 Run。 */
test("Plan fixture rejects mismatched or incomplete committed receipts", () => {
  const context = { kind: "plan", planId: "plan_fixture", runId: "run_fixture" };
  const call = {
    type: "function_call",
    call_id: "call_step",
    name: "plan_step_update",
    arguments: JSON.stringify({ ...context, status: "running" }),
  };
  const receipt = {
    aggregateId: context.planId,
    callId: "call_step",
    toolName: "plan_step_update",
    status: "committed",
  };
  for (const result of [
    { output: JSON.stringify({ ...receipt, callId: "call_other" }) },
    { output: JSON.stringify({ ...receipt, aggregateId: "plan_other" }) },
    { output: JSON.stringify(receipt), status: "incomplete" },
    { output: JSON.stringify(receipt), error: true },
  ]) {
    const input = [call, { type: "function_call_output", call_id: "call_step", ...result }];
    assert.equal(planFixtureStepStatus(input, context), "ready");
    assert.equal(nextPlanToolName(input), "plan_step_update");
  }
});

/** 失败的 SUCCEEDED 请求仍保留 RUNNING，不能制造假完成和跳过后续工具纠正。 */
test("Plan fixture does not finalize a failed succeeded request", () => {
  const context = {
    kind: "plan",
    planId: "plan_fixture",
    runId: "run_fixture",
    stepStatus: "running",
  };
  const input = [
    {
      type: "function_call",
      call_id: "call_step",
      name: "plan_step_update",
      arguments: JSON.stringify({ ...context, status: "succeeded" }),
    },
    { type: "function_call_output", call_id: "call_step", output: "PLAN_INVALID_STATE" },
  ];
  assert.equal(planFixtureStepStatus(input, context), "running");
  assert.equal(nextPlanToolName(input), "plan_step_update");
});

/** 当前连接失败优先于旧卡片，且诊断不复制任意错误正文或私有字段。 */
test("Interaction/Plan condition rejects a crashed current generation before accepting a stale card", async () => {
  let predicateCalls = 0;
  await assert.rejects(
    waitForInteractionPlanRuntimeCondition(
      "pending Interaction",
      () => {
        predicateCalls += 1;
        return true;
      },
      async () => ({
        nativeState: { status: "crashed", generation: 2, message: "private-token" },
        statusEvents: [{ status: "crashed", generation: 2, reason: "sidecar_eof" }],
      }),
      Date.now() + 1_000,
    ),
    (error) => {
      assert.match(error.message, /"status":"crashed"/u);
      assert.match(error.message, /"reason":"sidecar_eof"/u);
      assert.doesNotMatch(error.message, /private-token/u);
      return true;
    },
  );
  assert.equal(predicateCalls, 0);
});

/** 重启之前的 EOF 不污染新代际；真实条件仍以调用方的观测值返回。 */
test("Interaction/Plan condition ignores an old generation failure", async () => {
  const expected = { interactionId: "interaction-current" };
  const result = await waitForInteractionPlanRuntimeCondition(
    "pending Interaction",
    () => expected,
    async () => ({
      nativeState: { status: "ready", generation: 2 },
      statusEvents: [{ status: "crashed", generation: 1, reason: "sidecar_eof" }],
    }),
    Date.now() + 1_000,
  );
  assert.equal(result, expected);
});

/** 进入等待之后发生的断连应立即失败，不继续读取快照或耗尽长稳的全局期限。 */
test("Interaction/Plan condition stops polling after a live connection fails", async () => {
  let runtimeReads = 0;
  let predicateCalls = 0;
  await assert.rejects(
    waitForInteractionPlanRuntimeCondition(
      "pending Interaction",
      () => {
        predicateCalls += 1;
        return false;
      },
      async () => ({
        nativeState: { status: ++runtimeReads === 1 ? "ready" : "faulted", generation: 2 },
        statusEvents: [],
      }),
      Date.now() + 1_000,
    ),
    /"status":"faulted"/u,
  );
  assert.equal(runtimeReads, 2);
  assert.equal(predicateCalls, 1);
});

/** @author kongweiguang 按现有 aria-labelledby 找到唯一审批卡，避免依赖不可见的 callId 文本。 */
test("approves a Plan Tool through the exact existing accessible approval identity", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const cardSource = await readFile(
    new URL(
      "../../apps/desktop/src/features/conversation/ui/approval/ApprovalCard.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  const start = source.indexOf("async function approvePendingStandalonePlanTool(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const helper = source.slice(start, end);

  assert.ok(
    helper.includes('`section[aria-labelledby="ja-approval-${requested.approvalId}"]`'),
    "the approval card must be located by the requested approval's existing accessible identity",
  );
  assert.doesNotMatch(helper, /getByText\(requested\.callId/u);
  assert.match(helper, /approval\.decision === null/u);
  assert.match(helper, /turn\?\.status !== "waiting_approval"/u);
  assert.match(helper, /presentationStatus === "waiting_approval"/u);
  assert.match(helper, /approvalCard\.getByRole\("button", \{ name: "批准", exact: true \}\)/u);
  assert.match(helper, /approval\.approvalId === requested\.approvalId/u);
  assert.match(helper, /approval\.turnId === requested\.turnId/u);
  assert.match(helper, /approval\.callId === requested\.callId/u);
  assert.match(helper, /approval\.decision === "approve"/u);
  assert.match(cardSource, /aria-labelledby=\{`ja-approval-\$\{approval\.approvalId\}`\}/u);
  assert.match(cardSource, /<h3 id=\{`ja-approval-\$\{approval\.approvalId\}`\}>/u);
});

/** @author kongweiguang 验证单一 ordered init source 在真实 reload 入口前注册，并拒绝伪造或未知事实。 */
test("registers the Task exit seam before reload and rejects unknown lifecycle facts", async () => {
  const events = [];
  const initScripts = [];
  let reportExitEvent;
  const page = {
    exposeFunction: async (name, callback) => {
      assert.equal(name, "__JA_E2E_TASK_THREADS_EXIT_EVENT__");
      reportExitEvent = callback;
    },
    addInitScript: async (source) => initScripts.push(source),
  };
  await installTaskThreadsExitProbe(page, events);
  assert.equal(initScripts.length, 1);
  assert.equal(typeof initScripts[0], "string");

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n", mainStart);
  assert.ok(mainStart >= 0 && mainEnd > mainStart);
  const main = source.slice(mainStart, mainEnd);
  const install = main.indexOf("installTaskThreadsExitProbe(page, taskThreadsExitEvents)");
  const reload = main.indexOf("page.reload(", install);
  const ready = main.indexOf("const runtimeIdentity = await raceWithSignal(", reload);
  const freeze = main.indexOf("freezeSharedRuntimeForCleanup(", ready);
  const listenerReady = main.indexOf("waitForTaskThreadsExitListenerReady(", freeze);
  assert.ok(install >= 0 && reload > install, "probe registration must precede real reload");
  assert.ok(ready > reload && freeze > ready && listenerReady > freeze);

  const dom = new JSDOM("<main></main>", { runScripts: "outside-only" });
  try {
    dom.window.__JA_E2E_TASK_THREADS_EXIT_EVENT__ = reportExitEvent;
    dom.window.eval(initScripts[0]);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    assert.equal(typeof invoke, "function");
    assert.equal(dom.window.__JA_E2E_TASK_THREADS_EXIT_PROBE__.wrapper, invoke);
    assert.throws(
      () => reportExitEvent({ command: "ja_unknown_exit", phase: "start" }),
      /未知生命周期事实/u,
    );
    assert.throws(
      () => reportExitEvent({ command: "ja_app_exit_commit", phase: "payload" }),
      /未知生命周期事实/u,
    );
    assert.deepEqual(events, [], "probe contract checks must not synthesize a product ACK");
  } finally {
    dom.window.close();
  }
});

/** @author kongweiguang 锁定 page/browser 生命周期观测只保留安全枚举、阶段、时间与请求状态。 */
test("projects page and browser closure events without serializing page or request content", () => {
  const pageHandlers = new Map();
  const browserHandlers = new Map();
  let stage = "first:normal_exit";
  const diagnostics = attachPageDiagnostics(
    { on: (event, handler) => pageHandlers.set(event, handler) },
    undefined,
    () => stage,
    { on: (event, handler) => browserHandlers.set(event, handler) },
  );

  pageHandlers.get("close")();
  diagnostics.pageCloseRequested = true;
  pageHandlers.get("close")();
  stage = "first:cleanup";
  diagnostics.browserDisconnectRequested = true;
  browserHandlers.get("disconnected")();

  assert.deepEqual(
    diagnostics.lifecycleEvents.map(({ kind, stage: observedStage, requested }) => ({
      kind,
      stage: observedStage,
      requested,
    })),
    [
      { kind: "page_close", stage: "first:normal_exit", requested: false },
      { kind: "page_close", stage: "first:normal_exit", requested: true },
      { kind: "browser_disconnected", stage: "first:cleanup", requested: true },
    ],
  );
  assert.ok(
    diagnostics.lifecycleEvents.every((event) => Number.isFinite(event.observedAtMonotonicMs)),
  );
  assert.doesNotMatch(JSON.stringify(diagnostics), /https?:|payload|token|SECRET/u);
});

/** @author kongweiguang 确认父 launcher 只输出 wrapper 数值终态，既不混作 Native 状态也不泄露原始参数。 */
test("projects launcher wrapper exit and close timestamps as bounded numeric diagnostics", () => {
  assert.deepEqual(
    projectWrapperLifecycle({
      childPid: 41,
      output: {
        startedAtMonotonicMs: 10,
        stdout: ["SECRET_ARGUMENTS"],
        processExit: {
          code: 2,
          signal: null,
          observedAtMonotonicMs: 15,
          commandLine: "SECRET_COMMAND_LINE",
        },
        processClose: {
          code: 2,
          signal: null,
          observedAtMonotonicMs: 18,
        },
      },
      sampledAtMonotonicMs: 21,
    }),
    {
      pid: 41,
      elapsedMs: 11,
      exit: { code: 2, signal: null, observedAtMonotonicMs: 15 },
      close: { code: 2, signal: null, observedAtMonotonicMs: 18 },
    },
  );
  assert.doesNotMatch(
    JSON.stringify(
      projectWrapperLifecycle({
        childPid: 41,
        output: {
          startedAtMonotonicMs: 1,
          processExit: { code: null, signal: "SIGTERM", observedAtMonotonicMs: 2 },
        },
        sampledAtMonotonicMs: 3,
      }),
    ),
    /SECRET|token|commandLine/u,
  );
});

/** @author kongweiguang 失败边界投影仅泄露数值 PID 与 exact-identity 存在性，未知保持 null。 */
test("projects failure-time exact owner presence without paths, command lines, or server identity", () => {
  const launcher = {
    pid: 41,
    parentPid: 1,
    name: "cmd.exe",
    commandLine: "SECRET_LAUNCH_ARGS",
    creationDate: "20261008010000.000000+000",
  };
  const owner = {
    pid: 42,
    parentPid: 41,
    name: "java.exe",
    commandLine: "SECRET_NATIVE_TOKEN",
    creationDate: "20261008010001.000000+000",
  };
  const projected = projectFailureOwnerPresence({
    stage: "first:normal_exit",
    sampledAtMonotonicMs: 125.5,
    snapshotStatus: "fresh",
    rootIdentity: launcher,
    frozenOwner: owner,
    snapshot: [launcher, owner],
  });
  assert.deepEqual(projected, {
    stage: "first:normal_exit",
    sampledAtMonotonicMs: 125.5,
    snapshotStatus: "fresh",
    launcherPid: 41,
    launcherIdentityComplete: true,
    launcherPresent: true,
    nativeOwnerPid: 42,
    nativeOwnerFrozen: true,
    nativeOwnerPresent: true,
  });
  assert.doesNotMatch(
    JSON.stringify(projected),
    /SECRET|commandLine|creationDate|path|serverInstance/u,
  );
  const unavailable = projectFailureOwnerPresence({
    stage: "unsafe stage with payload",
    sampledAtMonotonicMs: 2,
    snapshotStatus: "unavailable",
    rootIdentity: launcher,
    frozenOwner: owner,
  });
  assert.equal(unavailable.stage, "unknown");
  assert.equal(unavailable.launcherPresent, null);
  assert.equal(unavailable.nativeOwnerPresent, null);
});

/** @author kongweiguang 锁定当前四项生产指标的真实路径/字段，并拒绝缺失、未知或被篡改的预检结果。 */
test("Task metric source contracts are checked before any desktop setup", async () => {
  const checks = await readTaskMetricDefinitionChecks();
  assert.deepEqual(checks, [
    {
      name: "task_active_count",
      sourcePath:
        "ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/collaboration/application/service/TaskCoordinator.java",
      fields: ["active_count"],
      present: true,
      fieldsPresent: true,
    },
    {
      name: "workspace_write_lease_wait",
      sourcePath:
        "ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/conversation/application/loop/WorkspaceWriteLeaseCoordinator.java",
      fields: ["wait_ms"],
      present: true,
      fieldsPresent: true,
    },
    {
      name: "task_recovery_suspended",
      sourcePath:
        "ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/repository/task/TaskRecoveryPersistence.java",
      fields: ["suspended_count"],
      present: true,
      fieldsPresent: true,
    },
    {
      name: "task_progress_coalesced_total",
      sourcePath: "crates/ja-runtime/src/app_server_process/client/session/events.rs",
      fields: ["count"],
      present: true,
      fieldsPresent: true,
    },
  ]);
  assert.equal(taskMetricDefinitionChecksAreValid(checks), true);
  assert.equal(taskMetricDefinitionChecksAreValid(undefined), false);
  assert.equal(taskMetricDefinitionChecksAreValid(null), false);
  assert.equal(taskMetricDefinitionChecksAreValid(checks.slice(1)), false);
  assert.equal(
    taskMetricDefinitionChecksAreValid(checks.map((check, index) => (index === 0 ? null : check))),
    false,
  );
  assert.equal(
    taskMetricDefinitionChecksAreValid(
      checks.map((check, index) => (index === 0 ? { ...check, present: null } : check)),
    ),
    false,
  );
  assert.equal(
    taskMetricDefinitionChecksAreValid(
      checks.map((check, index) => (index === 0 ? { ...check, fieldsPresent: false } : check)),
    ),
    false,
  );
  assert.equal(
    taskMetricDefinitionChecksAreValid(
      checks.map((check, index) => (index === 0 ? { ...check, fields: ["wrong_field"] } : check)),
    ),
    false,
  );
  assert.equal(
    taskMetricDefinitionChecksAreValid(
      checks.map((check, index) => (index === 0 ? { ...check, unexpected: true } : check)),
    ),
    false,
  );

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n", mainStart);
  assert.ok(mainStart >= 0 && mainEnd > mainStart);
  const main = source.slice(mainStart, mainEnd);
  assert.ok(
    main.indexOf("await readTaskMetricDefinitionChecks()") <
      main.indexOf("await createRunDirectories()"),
  );
  assert.ok(main.includes("taskMetricDefinitionChecksAreValid(taskMetricDefinitionChecks)"));
});

/** @author kongweiguang 锁定真实 thread/read resolve 诊断，防止为排障另发 RPC 或持久化会话正文/ID。 */
test("captures redacted Thread snapshot diagnostics at the real invoke resolve seam", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const probeStart = source.indexOf("export function installTauriInvokeProbeInPage()");
  const probeEnd = source.indexOf("\n}\n", probeStart);
  const interactionStart = source.indexOf(
    "async function runIndependentInteractionPlanAcceptanceSession(",
  );
  const interactionEnd = source.indexOf("\n}\n", interactionStart);
  const captureStart = source.indexOf("async function captureUiEvidence(");
  const captureEnd = source.indexOf("\n}\n", captureStart);
  assert.ok(probeStart >= 0 && probeEnd > probeStart);
  assert.ok(interactionStart > probeStart && interactionEnd > interactionStart);
  assert.ok(captureStart > probeEnd && captureEnd > captureStart);

  const probe = source.slice(probeStart, probeEnd);
  const diagnosticStart = probe.indexOf(
    "if (globalThis.__JA_E2E_CAPTURE_THREAD_READ_DIAGNOSTICS__ === true)",
  );
  const resolvedDiagnostic = probe.slice(
    diagnosticStart,
    probe.indexOf("if (turnLifecycleObserved)", diagnosticStart),
  );
  const interaction = source.slice(interactionStart, interactionEnd);
  const capture = source.slice(captureStart, captureEnd);
  const redactedProjectionStart = resolvedDiagnostic.indexOf(
    "diagnostics.push({",
    resolvedDiagnostic.indexOf('else if (command === "ja_thread_read")'),
  );
  const redactedProjectionEnd = resolvedDiagnostic.indexOf("});", redactedProjectionStart);
  const redactedProjection = resolvedDiagnostic.slice(
    redactedProjectionStart,
    redactedProjectionEnd,
  );
  assert.ok(probe.indexOf("const result = await delegate();") < diagnosticStart);
  assert.ok(resolvedDiagnostic.includes("ThreadReadResultSchema.safeParse(result)"));
  assert.ok(resolvedDiagnostic.includes("timelineSnapshotValidationReason(result)"));
  assert.ok(resolvedDiagnostic.includes('"waiting_input"'));
  assert.ok(resolvedDiagnostic.includes("threadIdMatchesRequest:"));
  assert.ok(resolvedDiagnostic.includes("latestListScope: listScopeKind"));
  assert.ok(resolvedDiagnostic.includes("latestListScopeWorkspaceIdPresent:"));
  assert.ok(resolvedDiagnostic.includes("requestedWorkspaceKind: workspaceKind"));
  assert.ok(resolvedDiagnostic.includes("[...globalThis.__JA_E2E_THREAD_LIST_WORKSPACES__]"));
  assert.ok(resolvedDiagnostic.includes("workspaceMatchesListScope,"));
  assert.ok(resolvedDiagnostic.includes("liveStream:"));
  assert.ok(resolvedDiagnostic.includes("nextCursor:"));
  assert.doesNotMatch(redactedProjection, /^\s*threadId\s*:/mu);
  assert.doesNotMatch(redactedProjection, /^\s*turnId\s*:/mu);
  assert.doesNotMatch(redactedProjection, /bodyTextPreview|outputPreview|answers\s*:/u);
  assert.ok(interaction.includes("page.evaluate(enableThreadReadDiagnosticsInPage)"));
  assert.ok(interaction.includes("page.addInitScript(enableThreadReadDiagnosticsInPage)"));
  assert.ok(capture.includes("captureThreadReadContractDiagnostics(page)"));
  assert.ok(capture.includes("options.skipAuthoritativeThreadRead === true"));
});

/** @author kongweiguang 固定现行 mutation 字段，并将 hidden Interaction 的退出留在 owner-verified cleanup。 */
test("uses operationKey and keeps hidden Interaction cleanup off the foreground tray path", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n", mainStart);
  const cleanupStart = source.indexOf("async function cleanupPhase(");
  const cleanupEnd = source.indexOf("\n}\n", cleanupStart);
  assert.ok(mainStart >= 0 && mainEnd > mainStart);
  assert.ok(cleanupStart > 0 && cleanupEnd > cleanupStart);
  const main = source.slice(mainStart, mainEnd);
  const cleanup = source.slice(cleanupStart, cleanupEnd);
  const cleanupSelectionStart = main.indexOf("const cleanupMode =");
  const cleanupSelectionEnd = main.indexOf('"owned_tree"', cleanupSelectionStart);
  const cleanupSelection = main.slice(cleanupSelectionStart, cleanupSelectionEnd);
  assert.ok(cleanupSelection.includes("interactionPlanAcceptanceMode"));
  assert.ok(cleanupSelection.includes('process.env.JA_E2E_HIDDEN_WINDOW_ONLY === "1"'));
  assert.ok(cleanup.includes('const requireTrayExit = cleanupMode === "tray_exit"'));
  assert.ok(cleanup.includes("if (requireTrayExit && tauriPids.length > 0)"));
  assert.doesNotMatch(source, /\bidempotencyKey\s*:/u);
  assert.match(source, /operationKey: `task-main-side-/u);
  assert.match(source, /operationKey: `task-side-main-/u);
  assert.match(source, /operationKey: `task-main-agent-/u);
  assert.match(source, /operationKey: `\$\{submission\.operationKey\}-stale`/u);
});

/** @author kongweiguang Theme Matrix 用真实选中 Thread，视觉操作只用本地 fixture 并保持零请求。 */
test("Theme Matrix binds its real Thread and Runtime before opening the Workbench", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const preparationStart = source.indexOf("async function prepareThemeMatrixWorkbench(");
  const preparationEnd = source.indexOf("\n}\n", preparationStart);
  const sessionStart = source.indexOf("async function runThemeMatrixAcceptanceSession(");
  const sessionEnd = source.indexOf("\n}\n", sessionStart);
  assert.ok(preparationStart >= 0 && preparationEnd > preparationStart);
  assert.ok(sessionStart > preparationStart && sessionEnd > sessionStart);
  const preparation = source.slice(preparationStart, preparationEnd);
  const session = source.slice(sessionStart, sessionEnd);
  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n", mainStart);
  assert.ok(mainStart >= 0 && mainEnd > mainStart);
  const main = source.slice(mainStart, mainEnd);

  assert.match(preparation, /currentThreadId\(page, stepDeadline, signal\)/u);
  assert.match(preparation, /selectProjectThreadById\(page, threadId, stepDeadline, signal\)/u);
  assert.match(preparation, /invoke\("ja_thread_read"/u);
  assert.match(preparation, /authority\.threadId !== threadId/u);
  assert.match(
    preparation,
    /readyBeforeThread\.serverInstanceId !== readyAfterThread\.serverInstanceId/u,
  );
  assert.match(preparation, /providerFixture\.attempts\.length !== providerAttemptsBefore/u);
  assert.match(session, /prepareThemeMatrixWorkbench\(page, deadline, signal, providerFixture\)/u);
  assert.match(session, /providerRequestCount !== 0/u);
  assert.match(main, /themeMatrixAcceptanceMode\s*\|\|\s*interactionPlanAcceptanceMode/u);
  assert.match(main, /runThemeMatrixAcceptanceSession\([\s\S]*?automaticTitleFixture,/u);
});

/** 固定 WebView 页面用自己的 DOM Node 常量判断文档顺序，避免浏览器回调误依赖 Node.js 全局。 */
test("browser document-order probes resolve the DOM Node constant in the page realm", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /globalThis\.Node\.DOCUMENT_POSITION_FOLLOWING/u);

  const dom = new JSDOM('<main><button id="first"></button><button id="second"></button></main>', {
    runScripts: "outside-only",
  });
  try {
    const follows = dom.window.Function(
      "first",
      "second",
      "return (first.compareDocumentPosition(second) & globalThis.Node.DOCUMENT_POSITION_FOLLOWING) !== 0;",
    );
    const first = dom.window.document.getElementById("first");
    const second = dom.window.document.getElementById("second");
    assert.equal(follows(first, second), true);
    assert.equal(follows(second, first), false);
  } finally {
    dom.window.close();
  }
});

/**
 * 仅模拟本回归使用的 Locator 操作，选择器由 JSDOM 真正执行并在每次读取时重查 DOM；
 * 不提供 first() 或可见性捷径，确保生产 helper 必须验证唯一 owner 与实际卸载。
 */
function retainedWorkbenchPage(document) {
  /** 子定位从父范围递归求值，允许关闭 Tab 后使用原 Locator 回读同一 owner。 */
  const locator = (roots, selector) => {
    /** 真实 CSS 匹配保留 Host 隔离标记，不能用手写数组提前替生产 helper 过滤。 */
    const elements = () => roots().flatMap((root) => [...root.querySelectorAll(selector)]);
    return {
      /** 数量必须独立读取，重复 owner 不能由 locator 自动选中第一个。 */
      count: async () => elements().length,
      /** 继续在父范围内定位，后台同名节点不得进入结果。 */
      locator: (childSelector) => locator(elements, childSelector),
      /** 单节点回读遵循 Playwright strict 行为，错误 scope 不能被 fixture 容忍。 */
      evaluate: async (callback, argument) => {
        const matches = elements();
        assert.equal(matches.length, 1);
        return callback(matches[0], argument);
      },
      /** 关闭 ACK 必须是 DOM 卸载；隐藏但保留的当前 Tab 仍应失败。 */
      waitFor: async ({ state, timeout }) => {
        assert.equal(state, "detached");
        assert.ok(timeout > 0);
        if (elements().length !== 0) throw new Error("current owner still contains tab");
      },
    };
  };
  return { locator: (selector) => locator(() => [document], selector) };
}

/** 抽屉收起时 inspector 自身不可见；后台 Thread 仍由 hidden/inert 隔离，ARIA 不决定 owner。 */
test("selects the current collapsed Workbench while retained Thread inspectors stay hidden", async () => {
  const dom = new JSDOM(`
    <section data-thread-workbench-scope="retained-hidden" hidden>
      <aside class="ja-inspector" aria-label="工作区面板" aria-hidden="true"></aside>
    </section>
    <section data-thread-workbench-scope="retained-aria" hidden aria-hidden="true" inert>
      <aside class="ja-inspector" aria-label="工作区面板" aria-hidden="true"></aside>
    </section>
    <section data-thread-workbench-scope="retained-inert" inert>
      <aside class="ja-inspector" aria-label="工作区面板" aria-hidden="true"></aside>
    </section>
    <section data-thread-workbench-scope="current">
      <aside id="current-inspector" class="ja-inspector" aria-label="工作区面板" aria-hidden="true"></aside>
    </section>
  `);
  try {
    const inspector = await currentThreadWorkbenchInspector(
      retainedWorkbenchPage(dom.window.document),
    );
    assert.equal(await inspector.evaluate((element) => element.id), "current-inspector");
    assert.equal(
      await inspector.evaluate((element) => element.hasAttribute("data-visible")),
      false,
    );
  } finally {
    dom.window.close();
  }
});

/** Portal 的 hideOthers 可遮蔽当前 Host 的 ARIA；真实 Thread wrapper 的 hidden/inert 才改变归属。 */
test("keeps the unique current Thread owner while a modal portal hides its ARIA subtree", async () => {
  const dom = new JSDOM(`
    <section data-thread-workbench-scope="ws:retained-thread" hidden aria-hidden="true" inert style="display: none;">
      <aside class="ja-inspector" aria-label="工作区面板" data-visible="true"></aside>
    </section>
    <section data-thread-workbench-scope="ws:current-thread" aria-hidden="true" style="display: contents;">
      <aside id="modal-background-inspector" class="ja-inspector" aria-label="工作区面板" data-visible="true">
        <div class="ja-workbench" data-active-tab="new" data-open-tabs="new">
          <div class="ja-workbench-tab-shell" data-tab="new"></div>
        </div>
      </aside>
    </section>
    <div role="menu" aria-label="新建标签页"><button role="menuitem">终端</button></div>
  `);
  try {
    const page = retainedWorkbenchPage(dom.window.document);
    const host = await currentThreadWorkbenchHost(page);
    assert.equal(
      await host.evaluate((element) => element.getAttribute("data-thread-workbench-scope")),
      "ws:current-thread",
    );
    assert.equal(await host.evaluate((element) => element.getAttribute("aria-hidden")), "true");
    const inspector = await currentThreadWorkbenchInspector(page);
    assert.equal(await inspector.evaluate((element) => element.id), "modal-background-inspector");
    assert.deepEqual(await readThreadWorkbenchState(page), {
      tabs: ["new"],
      active: "new",
      inspectorOpen: true,
      renderedTabs: ["new"],
    });
    dom.window.document
      .querySelector('[data-thread-workbench-scope="ws:current-thread"]')
      .removeAttribute("aria-hidden");
    assert.equal(
      await (
        await currentThreadWorkbenchHost(page)
      ).evaluate((element) => element.getAttribute("data-thread-workbench-scope")),
      "ws:current-thread",
    );
    dom.window.document.body.innerHTML +=
      '<section data-thread-workbench-scope="ws:duplicate-current" aria-hidden="true" style="display: contents;"></section>';
    await assert.rejects(currentThreadWorkbenchHost(page), /实际 2/u);
  } finally {
    dom.window.close();
  }
});

/** 后台同 key Tab、活动面板与顺序必须保留；关闭当前 Tab 的真实 ACK 只能受当前 owner 影响。 */
test("keeps retained Workbench tabs and active panels outside current Thread reads and close ACKs", async () => {
  const dom = new JSDOM(`
    <section data-thread-workbench-scope="retained" hidden aria-hidden="true" inert>
      <aside class="ja-inspector" aria-label="工作区面板">
        <div class="ja-workbench" data-active-tab="terminal" data-open-tabs="terminal,preview">
        <div class="ja-workbench-tab-shell" data-tab="terminal" data-state="active"></div>
        <div class="ja-workbench-tab-shell" data-tab="preview"></div>
        <div data-tab-panel="terminal"></div><div data-tab-panel="preview"></div>
        </div>
      </aside>
    </section>
    <section data-thread-workbench-scope="current">
      <aside class="ja-inspector" aria-label="工作区面板" data-visible="true">
        <div class="ja-workbench" data-active-tab="preview" data-open-tabs="preview,terminal">
        <div class="ja-workbench-tab-shell" data-tab="preview" data-state="active"></div>
        <div id="current-terminal" class="ja-workbench-tab-shell" data-tab="terminal" hidden></div>
        <div data-tab-panel="terminal" hidden></div><div data-tab-panel="preview"></div>
        </div>
      </aside>
    </section>
  `);
  try {
    const page = retainedWorkbenchPage(dom.window.document);
    const inspector = await currentThreadWorkbenchInspector(page);
    assert.deepEqual(await readThreadWorkbenchState(page), {
      tabs: ["preview", "terminal"],
      active: "preview",
      inspectorOpen: true,
      renderedTabs: ["preview", "terminal"],
    });
    assert.deepEqual(
      await inspector.evaluate((owner) => ({
        order: [...owner.querySelectorAll(".ja-workbench-tab-shell")].map((tab) =>
          tab.getAttribute("data-tab"),
        ),
        active: owner
          .querySelector('.ja-workbench-tab-shell[data-state="active"]')
          ?.getAttribute("data-tab"),
        panels: [...owner.querySelectorAll("[data-tab-panel]:not([hidden])")].map((panel) =>
          panel.getAttribute("data-tab-panel"),
        ),
      })),
      { order: ["preview", "terminal"], active: "preview", panels: ["preview"] },
    );
    await assert.rejects(
      waitForWorkbenchTabClosed(inspector, "terminal", Date.now() + 1_000),
      /current owner still contains tab/u,
    );
    dom.window.document.getElementById("current-terminal").remove();
    await waitForWorkbenchTabClosed(inspector, "terminal", Date.now() + 1_000);
    assert.equal(
      dom.window.document.querySelectorAll('.ja-workbench-tab-shell[data-tab="terminal"]').length,
      1,
    );
  } finally {
    dom.window.close();
  }
});

/** 确定性 paneId 可在后台重复；marker 只能由当前 owner 的执行输出完成，且须支持真实行折返。 */
test("reads Terminal markers from the current owner when retained panes reuse its paneId", async () => {
  const dom = new JSDOM(`
    <section data-thread-workbench-scope="retained" hidden>
      <div class="ja-terminal-pane" data-pane-id="pane-1"><div class="xterm-rows"><div>ja_marker</div></div></div>
    </section>
    <section data-thread-workbench-scope="current">
      <aside class="ja-inspector" aria-label="工作区面板">
        <div class="ja-terminal-pane" data-pane-id="pane-1"><div id="current-rows" class="xterm-rows"><div>waiting</div></div></div>
      </aside>
    </section>
  `);
  try {
    const owner = await currentThreadWorkbenchInspector(retainedWorkbenchPage(dom.window.document));
    const pane = owner.locator('.ja-terminal-pane[data-pane-id="pane-1"]');
    assert.equal(await terminalPaneContainsOutputMarker(pane, "ja_marker"), false);
    dom.window.document.getElementById("current-rows").innerHTML =
      "<div>ja_mar</div><div>ker</div>";
    assert.equal(await terminalPaneContainsOutputMarker(pane, "ja_marker"), true);
  } finally {
    dom.window.close();
  }
});

/** 卡住的 owner evaluate 必须被独立 marker 预算唤醒；阶段已取消时不能继续访问页面。 */
test("bounds Terminal owner marker reads and preserves stage cancellation", async () => {
  const pendingPane = { evaluate: () => new Promise(() => {}) };
  await assert.rejects(
    waitForTerminalPaneOutputMarker(pendingPane, "marker", Date.now() + 10),
    /Terminal owner marker 超过/u,
  );
  const controller = new AbortController();
  const cancellation = new Error("marker stage cancelled");
  controller.abort(cancellation);
  let evaluated = false;
  const pane = {
    evaluate: async () => {
      evaluated = true;
      return true;
    },
  };
  await assert.rejects(
    waitForTerminalPaneOutputMarker(pane, "marker", Date.now() + 1_000, controller.signal),
    (error) => error === cancellation,
  );
  assert.equal(evaluated, false);
  assert.equal(await waitForTerminalPaneOutputMarker(pane, "marker", Date.now() + 1_000), true);
});

/** 稳定但超宽的真实测量必须及时交给外层 64px 断言，保留可诊断的前后几何与采样数。 */
test("returns stable over-width Terminal geometry for the caller's narrow-pane diagnostic", async () => {
  const dom = new JSDOM(
    '<div role="tabpanel"><div class="ja-terminal-pane" data-pane-id="pane-1"></div></div>',
  );
  try {
    const target = dom.window.document.querySelector(".ja-terminal-pane");
    target.getBoundingClientRect = () => ({
      width: 128,
      left: 10,
      right: 138,
      top: 20,
      bottom: 120,
      height: 100,
    });
    let disposed = 0;
    const handle = {
      evaluate: async (callback) => callback(target),
      dispose: async () => {
        disposed += 1;
      },
    };
    const pane = { elementHandle: async () => handle };
    const geometry = await waitForStableTerminalPaneWidth(pane, Date.now() + 800);

    assert.equal(geometry.width, 128);
    assert.equal(geometry.stableSamples, 2);
    assert.equal(geometry.attempts, 2);
    assert.deepEqual(geometry.actualBefore, {
      width: 128,
      left: 10,
      right: 138,
      top: 20,
      bottom: 120,
      height: 100,
    });
    assert.deepEqual(geometry.actualAfter, geometry.actualBefore);
    assert.equal(disposed, 1);
  } finally {
    dom.window.close();
  }
});

/** 窄 pane 回归须使用真实窗口和指针布局，并在 finally 中恢复窗口及持久比例。 */
test("locks the narrow Terminal menu smoke to real resize and restoration", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const workflowStart = source.indexOf("async function exerciseTerminalWorkspace(");
  const workflowEnd = source.indexOf("/**\n * 目录属于可解析的本地目标", workflowStart);
  const ratioStart = source.indexOf("async function setTerminalPaneSplitRatio(");
  const ratioEnd = source.indexOf("/**\n * 通过 live handle 请求扩大第一支", ratioStart);
  const narrowStart = source.indexOf("async function narrowTerminalPaneWithSplitHandle(");
  const narrowEnd = source.indexOf("/**\n * 用当前 pane 的可访问名称定位 More", narrowStart);
  const filesWaitStart = source.indexOf("async function waitForStableFilesTreeGeometry(");
  const filesWaitEnd = source.indexOf("async function exerciseFilesTreeScroll(", filesWaitStart);
  const terminalWaitStart = source.indexOf("export async function waitForStableTerminalPaneWidth(");
  const terminalWaitEnd = source.indexOf(
    "async function setTerminalPaneSplitRatio(",
    terminalWaitStart,
  );
  assert.ok(workflowStart >= 0 && workflowEnd > workflowStart);
  assert.ok(ratioStart >= 0 && ratioEnd > ratioStart);
  assert.ok(narrowStart >= 0 && narrowEnd > narrowStart);
  assert.ok(filesWaitStart >= 0 && filesWaitEnd > filesWaitStart);
  assert.ok(terminalWaitStart >= 0 && terminalWaitEnd > terminalWaitStart);

  const workflow = source.slice(workflowStart, workflowEnd);
  const ratio = source.slice(ratioStart, ratioEnd);
  const narrow = source.slice(narrowStart, narrowEnd);
  const filesWait = source.slice(filesWaitStart, filesWaitEnd);
  const terminalWait = source.slice(terminalWaitStart, terminalWaitEnd);
  assert.match(workflow, /\{ width: 1280, height: 900 \}/u);
  assert.match(workflow, /minimumWorkbenchSize/);
  assert.match(
    workflow,
    /finally \{[\s\S]*?maximizeOwnedNativeWindow\([\s\S]*?resizeWorkbenchWithPointer\(/u,
  );
  assert.match(workflow, /layout: narrowLayoutEvidence/u);
  assert.match(ratio, /:scope > \.ja-terminal-split-handle/u);
  assert.match(ratio, /aria-valuemin/u);
  assert.match(ratio, /aria-valuemax/u);
  assert.match(ratio, /const minimumRatio = minimumPercent \/ 100/u);
  assert.match(ratio, /const maximumRatio = maximumPercent \/ 100/u);
  assert.match(
    ratio,
    /const effectiveRatio = Math\.min\(maximumRatio, Math\.max\(minimumRatio, ratio\)\)/u,
  );
  assert.match(ratio, /const availableExtent = splitBox\.width - handleBox\.width/u);
  assert.match(ratio, /splitBox\.x \+ handleBox\.width \/ 2 \+ availableExtent \* effectiveRatio/u);
  assert.match(ratio, /element\.addEventListener\("pointerdown"/u);
  assert.match(ratio, /element\.addEventListener\("pointerup"/u);
  assert.match(ratio, /pointerObservation\.pointerUp/u);
  assert.match(ratio, /deriveTerminalPaneRatioFromPointerEvidence\(pointerObservation\)/u);
  assert.doesNotMatch(ratio, /const expectedRatio = effectiveRatio \* 100/u);
  assert.doesNotMatch(ratio, /Math\.round\(ratio \* 100\)/u);
  assert.match(ratio, /waitForStableTerminalPaneWidth\(/u);
  assert.match(
    narrow,
    /if \(resized\.paneWidth >= 64\)[\s\S]*?JSON\.stringify\(\{ current, resized \}\)/u,
  );
  assert.match(narrow, /geometry: resized\.geometry/u);
  assert.match(filesWait, /waitForCondition\(/u);
  assert.match(filesWait, /references\.evaluate\(readFilesTreeGeometryFromReferences\)/u);
  assert.match(filesWait, /identityChanged[\s\S]*?stableSamples = 0/u);
  assert.match(filesWait, /stableSamples >= 2/u);
  assert.doesNotMatch(filesWait, /requestAnimationFrame/u);
  assert.match(terminalWait, /waitForCondition\(/u);
  assert.match(terminalWait, /paneHandle\.evaluate\(/u);
  assert.match(terminalWait, /identityChanged[\s\S]*?stableSamples = 0/u);
  assert.match(terminalWait, /stableSamples >= 2/u);
  assert.doesNotMatch(terminalWait, /requestAnimationFrame/u);
  assert.match(workflow, /createDeadline\("Terminal 窄窗恢复清理", 5_000\)/u);
  assert.match(workflow, /cleanupDeadline\.cancel\(\)/u);
  assert.match(workflow, /new AggregateError\([\s\S]*?\{ cause: workflowFailure \}/u);
});

/** 期望比例必须来自本次 release 事件和 pointerdown 抓取偏移，而非 runner 的目标坐标。 */
test("derives Terminal split ratio from matching pointerup and live release geometry", () => {
  const pointerObservation = {
    pointerDown: {
      bindingId: "same-handle",
      pointerId: 17,
      clientX: 150,
      geometry: { handle: { x: 140, width: 16 } },
    },
    pointerUp: {
      bindingId: "same-handle",
      pointerId: 17,
      clientX: 325,
      geometry: {
        split: { x: 100, width: 500 },
        handle: { x: 308, width: 16 },
        minimumRatio: 0.1,
        maximumRatio: 0.9,
      },
    },
  };
  const evidence = deriveTerminalPaneRatioFromPointerEvidence(pointerObservation);
  assert.equal(evidence.grabOffset, 2);
  assert.equal(evidence.availableExtent, 484);
  assert.equal(evidence.pointerDownX, 150);
  assert.equal(evidence.pointerUpX, 325);
  assert.ok(Math.abs(evidence.expectedPercent - (215 / 484) * 100) < 1e-12);
  assert.notEqual(evidence.expectedPercent, 80);
  assert.throws(
    () =>
      deriveTerminalPaneRatioFromPointerEvidence({
        ...pointerObservation,
        pointerUp: { ...pointerObservation.pointerUp, pointerId: 18 },
      }),
    /pointerId/u,
  );
});

/** 失败 summary 要保留 primary、AggregateError cause/errors、恢复结果及操作期间的最近观测。 */
test("retains Terminal last observation and AggregateError restoration diagnostics", () => {
  const primary = new Error("ratio commit timed out");
  const restorationFailure = new Error("Workbench restore timed out");
  const aggregateError = new AggregateError(
    [primary, restorationFailure],
    "Terminal workflow and cleanup failed",
    { cause: primary },
  );
  const lastObservation = {
    stage: "ratio_commit_wait",
    committedPercent: 44.4,
    expectedRatio: 44.42148760330579,
  };
  const restoration = {
    nativeResizeAttempted: true,
    workbenchResizeAttempted: true,
    restoredNativeViewport: { width: 1280, height: 900 },
    restoredWorkbench: null,
  };

  const summary = terminalWorkspaceFailureSummary({
    primaryError: primary,
    aggregateError,
    restorationFailures: [restorationFailure],
    restoration,
    lastObservation,
  });

  assert.equal(summary.primary.message, "ratio commit timed out");
  assert.equal(summary.cause.message, "ratio commit timed out");
  assert.deepEqual(
    summary.errors.map(({ message }) => message),
    ["ratio commit timed out", "Workbench restore timed out"],
  );
  assert.equal(summary.restorationFailures[0].message, "Workbench restore timed out");
  assert.deepEqual(summary.restoration, restoration);
  assert.deepEqual(summary.lastObservation, lastObservation);
});

/** 诊断永久挂起仍有上界，双错不能覆盖投影 Error，单独截图失败也不能当作已完成取证。 */
test("bounds partial-close diagnostics and keeps projection and screenshot failures distinct", async () => {
  /** 未 settle 的 CDP Promise 不会自行唤醒，必须由本地 deadline 拒绝外层等待。 */
  const pending = () => new Promise(() => {});
  let screenshotAttempts = 0;
  await assert.rejects(
    captureTerminalPartialCloseDiagnostics(
      pending,
      async () => {
        screenshotAttempts += 1;
      },
      Date.now() + 10,
    ),
    /Terminal partial projection 超过/u,
  );
  assert.equal(screenshotAttempts, 1);
  const projection = { panes: ["failed", "running"], retryVisible: true };
  await assert.rejects(
    captureTerminalPartialCloseDiagnostics(async () => projection, pending, Date.now() + 10),
    (error) =>
      error.message === "Terminal 部分关闭截图取证失败" &&
      /Terminal partial screenshot 超过/u.test(error.cause?.message),
  );
  const projectionCause = new Error("native projection cause");
  const projectionFailure = new Error("original projection failure", { cause: projectionCause });
  const screenshotFailure = new Error("screenshot rejected");
  await assert.rejects(
    captureTerminalPartialCloseDiagnostics(
      async () => {
        throw projectionFailure;
      },
      async () => {
        throw screenshotFailure;
      },
      Date.now() + 1_000,
    ),
    (error) => {
      assert.equal(error, projectionFailure);
      assert.equal(error.cause, projectionCause);
      assert.equal(error.screenshotError, screenshotFailure.message);
      return true;
    },
  );
  await assert.rejects(
    captureTerminalPartialCloseDiagnostics(
      async () => projection,
      async () => {
        throw screenshotFailure;
      },
      Date.now() + 1_000,
    ),
    (error) => error.cause === screenshotFailure,
  );
  assert.equal(
    await captureTerminalPartialCloseDiagnostics(
      async () => projection,
      async () => {},
      Date.now() + 1_000,
    ),
    projection,
  );
});

/** 当前首版 envelope 只持久化显示偏好；旧版本、退役尺寸和 Thread 状态残留都必须拒绝。 */
test("accepts the canonical global display preference schema without persisted Thread state", async () => {
  const source = await readFile(
    new URL("../../apps/desktop/src/shared/preferences/uiPreferences.ts", import.meta.url),
    "utf8",
  );
  const canonical = source.match(/name:\s*"ja-ui-preferences-v1",\s*version:\s*(\d+),/u);
  assert.ok(canonical, "当前显示偏好必须有明确的版本与介质键");
  const preference = {
    version: Number(canonical[1]),
    workbenchSize: 40.725,
    retiredInspectorSize: false,
    retiredInspectorRatio: false,
    retiredConversationRatio: false,
    retiredThreadState: false,
  };
  assert.doesNotThrow(() => assertWorkbenchPreferenceEvidence(preference));
  assert.throws(
    () => assertWorkbenchPreferenceEvidence({ ...preference, version: 14 }),
    /首版约束/u,
  );
  assert.throws(
    () => assertWorkbenchPreferenceEvidence({ ...preference, workbenchSize: Number.NaN }),
    /首版约束/u,
  );
  for (const field of [
    "retiredInspectorSize",
    "retiredInspectorRatio",
    "retiredConversationRatio",
    "retiredThreadState",
  ]) {
    assert.throws(
      () => assertWorkbenchPreferenceEvidence({ ...preference, [field]: true }),
      /首版约束/u,
    );
  }
});

/** 活动 owner 缺失或重复都必须显式失败，避免 first() 或面板可见性过滤掩盖 Thread 隔离错误。 */
test("rejects missing or multiple active Thread Workbench owners", async () => {
  const dom = new JSDOM('<section data-thread-workbench-scope="retained" hidden></section>');
  try {
    const page = retainedWorkbenchPage(dom.window.document);
    await assert.rejects(currentThreadWorkbenchHost(page), /实际 0/u);
    dom.window.document.body.innerHTML +=
      '<section data-thread-workbench-scope="one"></section><section data-thread-workbench-scope="two"></section>';
    await assert.rejects(currentThreadWorkbenchHost(page), /实际 2/u);
  } finally {
    dom.window.close();
  }
});

/** 将 UNKNOWN 与本地上下文估算分开，避免 UI 和恢复验收退回“UNKNOWN 必定是未知圆环”。 */
test("context usage projection follows persisted estimates instead of provider certainty alone", () => {
  const profile = { contextWindowTokens: 128_000 };
  assert.equal(
    runtimeContextUsageCertainty({
      certainty: "UNKNOWN",
      estimated_context_tokens: 3_840,
      profile,
    }),
    "estimated",
  );
  assert.equal(
    runtimeContextUsageCertainty({ certainty: "UNKNOWN", estimated_context_tokens: null, profile }),
    "unknown",
  );
  assert.equal(
    runtimeContextUsageCertainty({ certainty: "KNOWN", total_tokens: 0, profile }),
    "known",
  );
  assert.equal(
    runtimeContextUsageCertainty({
      certainty: "UNKNOWN",
      estimated_context_tokens: 3_840,
      profile: { contextWindowTokens: 0 },
    }),
    "unknown",
  );
  assert.deepEqual(
    runtimeContextUsageProjection({
      certainty: "UNKNOWN",
      estimated_context_tokens: 3_840,
      profile,
    }),
    {
      certainty: "estimated",
      usedTokens: 3_840,
      limitTokens: 128_000,
      rawPercentage: 3,
      percentage: 3,
      ringPercentage: 3,
      tone: "neutral",
    },
  );
  assert.equal(
    runtimeContextUsageProjection({
      certainty: "KNOWN",
      total_tokens: 95_000,
      profile: { contextWindowTokens: 100_000 },
    }).tone,
    "danger",
  );
  assert.deepEqual(runtimeContextUsageProjection({ certainty: "UNKNOWN", profile }), {
    certainty: "unknown",
  });
});

/** 只接受带精确 Workspace 身份的 Responses input_text 清单，防止兼容旧文案或同名路径误通过。 */
test("Composer context projection verifies structured manifests without accepting preloaded bodies", () => {
  const expectedWorkspaceId = "ws_composer_fixture";
  const payload = {
    input: [
      {
        role: "user",
        content: [
          {
            type: "input_text",
            text:
              "Resource reference [file] ws_composer_fixture:sample.ts (content not preloaded).\n" +
              "Resource reference [directory] ws_composer_fixture:folder-fixture (content not preloaded).",
          },
        ],
      },
    ],
  };

  assert.deepEqual(composerContextProjection(payload, expectedWorkspaceId), {
    skillActivated: false,
    fileReferenceProjected: true,
    directoryReferenceProjected: true,
    workspaceBodyAbsent: true,
  });
  assert.equal(composerContextProjection(payload, "ws_other").fileReferenceProjected, false);
  assert.equal(
    composerContextProjection(
      {
        input: [
          {
            role: "user",
            content: [
              {
                type: "input_text",
                text: "Resource reference [directory] ws_composer_fixture:sample.ts (content not preloaded).",
              },
            ],
          },
        ],
      },
      expectedWorkspaceId,
    ).fileReferenceProjected,
    false,
  );
  assert.equal(
    composerContextProjection(
      {
        input: [
          {
            role: "user",
            content: [
              {
                type: "input_text",
                text: "Resource reference [file] ws_composer_fixture:other.ts (content not preloaded).",
              },
            ],
          },
        ],
      },
      expectedWorkspaceId,
    ).fileReferenceProjected,
    false,
  );
  assert.equal(
    composerContextProjection(
      {
        input: [
          {
            role: "user",
            content: [
              {
                type: "input_text",
                text: "Workspace reference [file] sample.ts (content not preloaded).",
              },
            ],
          },
        ],
      },
      expectedWorkspaceId,
    ).fileReferenceProjected,
    false,
  );
  assert.equal(
    composerContextProjection(
      {
        ...payload,
        input: [
          {
            role: "user",
            content: [
              ...payload.input[0].content,
              { type: "input_text", text: 'export const greeting = "hello from Ja"' },
            ],
          },
        ],
      },
      expectedWorkspaceId,
    ).workspaceBodyAbsent,
    false,
  );
  const preloadedBody = 'export const greeting = "hello from Ja"';
  for (const payloadWithHiddenBody of [
    { ...payload, instructions: preloadedBody },
    { ...payload, input: [{ role: "system", content: preloadedBody }] },
    {
      ...payload,
      input: [{ role: "developer", content: [{ type: "input_text", text: preloadedBody }] }],
    },
  ]) {
    assert.equal(
      composerContextProjection(payloadWithHiddenBody, expectedWorkspaceId).workspaceBodyAbsent,
      false,
    );
  }
  assert.equal(
    composerContextProjection(
      {
        input: [
          {
            role: "developer",
            content: [
              {
                type: "input_text",
                text: "Resource reference [file] ws_composer_fixture:sample.ts (content not preloaded).",
              },
            ],
          },
        ],
      },
      expectedWorkspaceId,
    ).fileReferenceProjected,
    false,
  );
});

/** @author kongweiguang 即使元数据永不返回，失败取证也须保留可定位的截图/manifest 绝对路径。 */
test("bounds failure visual metadata reads and respects stage cancellation", async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), "ja-failure-visual-"));
  const directories = { home: outputDirectory, data: outputDirectory, runtime: outputDirectory };
  const page = {
    screenshot: async () => Buffer.from("fixture screenshot"),
    title: () => new Promise(() => {}),
    evaluate: () => new Promise(() => {}),
    url: () => "http://127.0.0.1/",
  };
  try {
    const evidence = await captureFailureVisualEvidence(
      page,
      "run_fixture",
      "first",
      new Error("original failure"),
      directories,
      { outputDirectory, metadataTimeoutMs: 10 },
    );
    assert.equal(evidence.status, "captured");
    assert.equal(evidence.metadataError, "failure visual metadata 超过 10ms");
    assert.equal(evidence.pageTitle, undefined);
    assert.equal(evidence.manifestError, undefined);
    assert.equal(evidence.originalFailure, "original failure");
    assert.equal(evidence.screenshotPath, join(outputDirectory, evidence.screenshotFile));
    assert.equal(evidence.manifestPath, `${evidence.screenshotPath}.manifest.json`);
    const screenshot = await readFile(evidence.screenshotPath);
    assert.equal(screenshot.toString(), "fixture screenshot");
    assert.equal(createHash("sha256").update(screenshot).digest("hex"), evidence.screenshotSha256);
    const manifest = JSON.parse(await readFile(evidence.manifestPath, "utf8"));
    assert.equal(manifest.screenshotPath, evidence.screenshotPath);
    assert.equal(manifest.manifestPath, evidence.manifestPath);

    const stageController = new AbortController();
    const stageCancellation = setTimeout(
      () => stageController.abort(new Error("stage cancelled")),
      10,
    );
    try {
      const cancelled = await captureFailureVisualEvidence(
        page,
        "run_cancelled",
        "first",
        new Error("original failure"),
        directories,
        { signal: stageController.signal, outputDirectory, metadataTimeoutMs: 1_000 },
      );
      assert.equal(cancelled.status, "captured");
      assert.equal(cancelled.metadataError, "stage cancelled");
    } finally {
      clearTimeout(stageCancellation);
    }
  } finally {
    await rm(outputDirectory, { recursive: true, force: true });
  }
});

/** 失败 manifest 回读必须保留真实 scope 属性与选中 Thread，不把菜单 ARIA 遮蔽误报为 owner 丢失。 */
test("records Thread identity and Workbench isolation attributes in bounded failure metadata", async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), "ja-failure-owner-"));
  const directories = { home: outputDirectory, data: outputDirectory, runtime: outputDirectory };
  const dom = new JSDOM(
    `
    <nav aria-label="最近对话列表"><button aria-current="page" data-thread-id="current-thread"></button></nav>
    <section data-thread-workbench-scope="ws:retained-thread" hidden aria-hidden="true" inert style="display: none;">
      <aside class="ja-inspector" data-visible="true"></aside>
    </section>
    <section data-thread-workbench-scope="ws:current-thread" aria-hidden="true" style="display: contents;">
      <aside class="ja-inspector" data-visible="true"></aside>
    </section>
  `,
    { runScripts: "outside-only" },
  );
  try {
    /** 元数据在页面 realm 执行同一回调，fixture 只替代无关的截图字节和 title。 */
    const page = {
      screenshot: async () => Buffer.from("fixture screenshot"),
      title: async () => "Ja",
      evaluate: async (callback) => dom.window.Function(`return (${callback.toString()})();`)(),
      url: () => "http://127.0.0.1/",
    };
    const evidence = await captureFailureVisualEvidence(
      page,
      "run_owner",
      "first",
      new Error("owner failure"),
      directories,
      { outputDirectory, metadataTimeoutMs: 1_000 },
    );
    assert.equal(evidence.metadataError, undefined);
    const manifest = JSON.parse(await readFile(evidence.manifestPath, "utf8"));
    assert.equal(manifest.threadWorkbench.selectedThreadId, "current-thread");
    assert.equal(manifest.threadWorkbench.hostCount, 2);
    assert.equal(manifest.threadWorkbench.truncated, false);
    assert.deepEqual(manifest.threadWorkbench.hosts, [
      {
        scope: "ws:retained-thread",
        hidden: true,
        inert: true,
        ariaHidden: "true",
        inlineDisplay: "none",
        inspectorVisible: "true",
      },
      {
        scope: "ws:current-thread",
        hidden: false,
        inert: false,
        ariaHidden: "true",
        inlineDisplay: "contents",
        inspectorVisible: "true",
      },
    ]);
  } finally {
    dom.window.close();
    await rm(outputDirectory, { recursive: true, force: true });
  }
});

/** 生产投影会在正文摘要后追加结构化错误元数据，审计仍只覆盖原始 Tool 正文。 */
test("audits tool output before its ordered error metadata", () => {
  const content = "Tool failed with code MCP_TOOL_FAILED (category: tool_binding_unavailable).";
  const digest = createHash("sha256").update(content, "utf8").digest("hex");
  const projected = `${content}\n[characters=${Array.from(content).length} sha256=${digest}]\n[error=tool_error]`;

  assert.deepEqual(runtimeRefreshParseAuditedText(projected), {
    valid: true,
    content,
    codePointCountValid: true,
    sha256Valid: true,
    exitCode: null,
    error: "tool_error",
  });
  const changedDigest = `${digest[0] === "0" ? "1" : "0"}${digest.slice(1)}`;
  assert.equal(
    runtimeRefreshParseAuditedText(projected.replace(digest, changedDigest)).reason,
    "footer_mismatch",
  );
  assert.equal(
    runtimeRefreshParseAuditedText(`${projected}\n[unexpected=true]`).reason,
    "missing_footer",
  );

  const unicodeContent = "检查结果 🚦";
  const unicodeDigest = createHash("sha256").update(unicodeContent, "utf8").digest("hex");
  const unicodeProjection = `${unicodeContent}\n[characters=${Array.from(unicodeContent).length} sha256=${unicodeDigest}]`;
  assert.equal(runtimeRefreshParseAuditedText(unicodeProjection).content, unicodeContent);
});

/** 执行 Core/V1 原始 DDL 并开真实文件库，确保只读 reader 不会靠手写旧表 fixture 通过。 */
async function withCurrentKernelDatabase(callback) {
  const data = await mkdtemp(join(tmpdir(), "ja-desktop-sqlite-contract-"));
  const database = new DatabaseSync(join(data, "ja.db"));
  try {
    for (const path of [
      "../../ja-agent/extensions/ja-agent-store-local/src/main/resources/io/github/kongweiguang/ja/agent/store/local/infrastructure/schema.sql",
      "../../ja-agent/ja-app-server/src/main/resources/db/migration/V1__kernel.sql",
    ])
      database.exec(await readFile(new URL(path, import.meta.url), "utf8"));
    database.exec("PRAGMA foreign_keys=ON");
    database.exec(
      "INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) " +
        "VALUES('ws_fixture','C:/fixture','PROJECT','Fixture','TRUSTED',1,1);" +
        "INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms," +
        "provider_id,model_id,access_mode,title_source) VALUES('thread_fixture','user_local','ws_fixture'," +
        "'Fixture',1,1,'provider_fixture','model_fixture','FULL_ACCESS','MANUAL');" +
        "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,terminal_summary) " +
        "VALUES('turn_fixture','thread_fixture','COMPLETED',1,2,2,'Completed');" +
        "INSERT INTO agent_conversation(tenant_id,thread_id,user_id,revision,event_sequence,record_sequence," +
        "latest_execution_sequence,created_at_ms,updated_at_ms) " +
        "VALUES('ja.local','thread_fixture','user_local',1,1,1,1,1,2)",
    );
    const cursor = {
      schemaVersion: 4,
      kind: "READY",
      next: "COMPLETION",
      summary: null,
      common: {
        modelRound: 2,
        usedToolCalls: 1,
        nextProviderOrdinal: 3,
        promptRevision: null,
        activeSkills: [],
      },
    };
    database
      .prepare(
        "INSERT INTO agent_execution(tenant_id,thread_id,turn_id,user_id,admission_sequence,state,mutation_version," +
          "cursor_schema_version,cursor_json,last_record_sequence,created_at_ms,updated_at_ms) " +
          "VALUES('ja.local','thread_fixture','turn_fixture','user_local',1,'COMPLETED',3,4,?,1,1,2)",
      )
      .run(JSON.stringify(cursor));
    await callback(database, { data }, cursor);
  } finally {
    database.close();
    await rm(data, { recursive: true, force: true });
  }
}

/** 成功终态必须保留真实 execution；缺行和 pending 游标都不能被旧“删除行”断言误放行。 */
test("Core SQL acceptance requires the retained COMPLETED/COMPLETION owner and current cursor counters", async () => {
  await withCurrentKernelDatabase((database, directories, cursor) => {
    assert.equal(coreExecutionCompleted(directories, "turn_fixture"), true);
    assert.equal(coreExecutionCompleted(directories, "turn_missing"), false);
    assert.deepEqual(runtimeRefreshExecutionFact(directories, "turn_fixture"), {
      schemaVersion: 4,
      mutationVersion: 3,
      kind: "READY",
      modelRound: 2,
      usedToolCalls: 1,
      nextProviderOrdinal: 3,
    });
    const facts = runtimeRefreshPersistenceFacts(directories, "turn_fixture");
    assert.equal(facts.coreExecutionCompleted, true);
    assert.deepEqual(facts.bindings, []);
    assert.deepEqual(facts.usage, []);
    assert.equal(facts.unavailableResultCount, 0);
    database
      .prepare("UPDATE agent_execution SET cursor_json=?")
      .run(JSON.stringify({ ...cursor, kind: "PROVIDER_PENDING" }));
    assert.equal(coreExecutionCompleted(directories, "turn_fixture"), false);
    database.prepare("UPDATE agent_execution SET cursor_schema_version=3").run();
    assert.throws(() => runtimeRefreshExecutionFact(directories, "turn_fixture"), /版本/u);
  });
});

/** App Server ledger 包含助手与摘要请求；SQLite 聚合必须保留每项 Token 的实际覆盖数。 */
test("Runtime Refresh usage reader reports persisted totals and per-field request coverage", async () => {
  await withCurrentKernelDatabase((database, directories) => {
    const insertUnknown = database.prepare(
      "INSERT INTO ja_turn_usage(usage_id,model_request_id,thread_id,turn_id,model_round," +
        "model_request_ordinal,purpose,certainty,profile_json,created_at_ms,input_accounting,estimated_context_tokens) " +
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
    );
    const insertRequest = (usageId, ordinal, purpose, createdAt, estimate = null) => {
      insertUnknown.run(
        usageId,
        `request_${usageId}`,
        "thread_fixture",
        "turn_fixture",
        1,
        ordinal,
        purpose,
        "UNKNOWN",
        "{}",
        createdAt,
        "UNKNOWN",
        estimate,
      );
    };
    const settle = database.prepare(
      "UPDATE ja_turn_usage SET certainty='KNOWN',input_tokens=?,new_input_tokens=?,output_tokens=?," +
        "total_tokens=?,created_at_ms=?,cache_read_tokens=?,cache_write_tokens=?,input_accounting=? " +
        "WHERE usage_id=?",
    );
    insertRequest("usage_known_assistant", 1, "ASSISTANT", 3);
    settle.run(100, 75, 20, 120, 4, 25, null, "INPUT_INCLUDES_CACHE", "usage_known_assistant");
    insertRequest("usage_unknown_assistant", 2, "ASSISTANT", 4, 60);
    insertRequest("usage_known_summary", 3, "SUMMARY", 5);
    settle.run(40, 40, 5, 45, 6, 10, 5, "INPUT_EXCLUDES_CACHE", "usage_known_summary");

    const facts = runtimeRefreshPersistenceFacts(directories, "turn_fixture", "thread_fixture");
    assert.equal(facts.usage.length, 2);
    assert.deepEqual(facts.ledger, {
      requestCount: 3,
      measuredRequestCount: 2,
      newInputRequestCount: 2,
      newInputTokens: 115,
      outputRequestCount: 2,
      outputTokens: 25,
      totalRequestCount: 2,
      totalTokens: 165,
      cacheReadRequestCount: 2,
      cacheReadTokens: 35,
      cacheWriteRequestCount: 1,
      cacheWriteTokens: 5,
      cacheCompleteRequestCount: 2,
      cacheCompleteInputTokens: 155,
      cacheCompleteReadTokens: 35,
    });
  });
});

/** DSML 放在 preview 之外；公开全文、模型 blocks 和当前最终 Timeline 三者缺一都必须拒绝。 */
test("DSML persistence reads full typed Core text and requires both model context and final Timeline ownership", async () => {
  await withCurrentKernelDatabase((database, directories) => {
    const text = `${"完整正文".repeat(200)} DSML ordinary text`;
    const payload = {
      messageId: "message_fixture",
      blocks: JSON.stringify([{ kind: "text", text }]),
      publicText: text,
      reasoningSummary: null,
      modelRound: 2,
    };
    database
      .prepare(
        "INSERT INTO agent_record(tenant_id,thread_id,turn_id,user_id,record_id,record_type,payload_schema_version," +
          "record_sequence,payload_json,occurred_at_ms) VALUES('ja.local','thread_fixture','turn_fixture','user_local'," +
          "'record_fixture','agent.assistant',1,1,?,2)",
      )
      .run(JSON.stringify(payload));
    database
      .prepare(
        "INSERT INTO ja_conversation_message(message_id,thread_id,turn_id,ordinal,role,kind,core_tenant_id," +
          "core_thread_id,core_turn_id,core_record_id,preview_text,total_characters,created_at_ms) " +
          "VALUES('message_fixture','thread_fixture','turn_fixture',1,'ASSISTANT','ASSISTANT','ja.local'," +
          "'thread_fixture','turn_fixture','record_fixture',?,?,2)",
      )
      .run(text.slice(0, 512), text.length);
    assert.equal(dsmlPersistenceRowCount(directories, "turn_fixture"), 0);
    database
      .prepare(
        "INSERT INTO ja_conversation_timeline_entry(item_id,thread_id,turn_id,sequence,kind,message_id," +
          "preview_text,total_characters,created_at_ms) VALUES('item_fixture','thread_fixture','turn_fixture',1," +
          "'FINAL_ANSWER','message_fixture',?,?,2)",
      )
      .run(text.slice(0, 512), text.length);
    assert.equal(dsmlPersistenceRowCount(directories, "turn_fixture"), 1);
    assert.equal(dsmlPersistenceRowCount(directories, "turn_missing"), 0);
    for (const changed of [
      { ...payload, publicText: "plain" },
      { ...payload, blocks: "[]" },
    ]) {
      database.prepare("UPDATE agent_record SET payload_json=?").run(JSON.stringify(changed));
      assert.equal(dsmlPersistenceRowCount(directories, "turn_fixture"), 0);
    }
    database.prepare("UPDATE agent_record SET payload_json=?").run(JSON.stringify(payload));
    database.prepare("UPDATE ja_conversation_timeline_entry SET is_current_path=0").run();
    assert.equal(dsmlPersistenceRowCount(directories, "turn_fixture"), 0);
  });
});

/** 实际 V1 验证 Mailbox 绑定只匹配传入的活动 Turn，并覆盖未消费与消费时间语义。 @author kongweiguang */
test("current V1 ledger, attachment and mailbox readers prepare actual schema and preserve numeric consumption time", async () => {
  await withCurrentKernelDatabase((database, directories) => {
    assert.equal(planExecutionLedgerFact(directories, "plan_missing", "run_missing"), undefined);
    assert.equal(attachmentOwnershipFact(directories, "attachment_missing"), undefined);
    assert.throws(
      () => queuedAttachmentFact(directories, "thread_fixture", "missing.txt"),
      /数量异常/u,
    );
    database
      .prepare(
        "INSERT INTO ja_task_mailbox_message(message_id,root_thread_id,from_thread_id,sender_title,to_thread_id," +
          "kind,content_json,operation_key,state,created_at_ms,updated_at_ms) VALUES('mailbox_fixture'," +
          "'thread_fixture','thread_sender','Sender','thread_fixture','MESSAGE','[]','mailbox_fixture','PENDING',1,1)",
      )
      .run();
    assert.deepEqual(taskThreadMessageFact(directories, "mailbox_fixture", "turn_fixture"), {
      rowCount: 1,
      state: "PENDING",
      boundTurnPresent: false,
      boundTurnMatchesActiveTurn: false,
      consumedAtPresent: false,
    });
    database
      .prepare(
        "UPDATE ja_task_mailbox_message SET state='CONSUMED',bound_turn_id='turn_fixture',consumed_at_ms=2",
      )
      .run();
    assert.deepEqual(taskThreadMessageFact(directories, "mailbox_fixture", "turn_fixture"), {
      rowCount: 1,
      state: "CONSUMED",
      boundTurnPresent: true,
      boundTurnMatchesActiveTurn: true,
      consumedAtPresent: true,
    });
    assert.equal(
      taskThreadMessageFact(directories, "mailbox_fixture", "different_active_turn")
        .boundTurnMatchesActiveTurn,
      false,
    );
  });
});

/** 读取真实文件证明当前 v2 可供真窗 owner 检查使用，并在边界拒绝旧握手且不返回 token。 */
test("reads only the current v2 private runtime endpoint without exposing its token", async () => {
  const runtime = await mkdtemp(join(tmpdir(), "ja-endpoint-contract-"));
  const endpointPath = join(runtime, "app-server.endpoint.json");
  const endpoint = {
    host: "127.0.0.1",
    port: 49152,
    protocolMajor: 2,
    protocolMinor: 0,
    runtimeGeneration: 1,
    serverInstanceId: "srv_endpoint_fixture",
    token: "a".repeat(64),
  };
  try {
    await writeFile(endpointPath, JSON.stringify(endpoint), "utf8");
    assert.deepEqual(await readRuntimeEndpointMetadata({ runtime }), {
      host: endpoint.host,
      port: endpoint.port,
      serverInstanceId: endpoint.serverInstanceId,
      runtimeGeneration: endpoint.runtimeGeneration,
    });
    await writeFile(endpointPath, JSON.stringify({ ...endpoint, protocolMajor: 1 }), "utf8");
    await assert.rejects(
      readRuntimeEndpointMetadata({ runtime }),
      /私有 endpoint 不可用或格式无效/u,
    );
  } finally {
    await rm(runtime, { recursive: true, force: true });
  }
});

/** 在单个测试作用域内提供最小 JA-RPC WebSocket，精确保留 ready generation 与 close 事件语义。 */
async function withFakeRuntimeWebSocket(options, exercise) {
  const previousDescriptor = Object.getOwnPropertyDescriptor(globalThis, "WebSocket");
  const sockets = [];
  class FakeRuntimeWebSocket extends EventTarget {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;

    /** 模拟异步 open，使 runner 必须走完整握手。 */
    constructor(url) {
      super();
      this.url = url;
      this.readyState = FakeRuntimeWebSocket.CONNECTING;
      this.sent = [];
      sockets.push(this);
      queueMicrotask(() => {
        this.readyState = FakeRuntimeWebSocket.OPEN;
        this.dispatchEvent(new Event("open"));
      });
    }

    /** 解析客户端帧并按选项发出确定性 JA-RPC 响应。 */
    send(payload) {
      const frame = JSON.parse(payload);
      this.sent.push(frame);
      if (frame.method === "runtime/initialize") {
        queueMicrotask(() =>
          this.dispatchEvent(
            new MessageEvent("message", {
              data: JSON.stringify({
                jsonrpc: "2.0",
                id: frame.id,
                result: { serverInstanceId: options.serverInstanceId },
              }),
            }),
          ),
        );
      } else if (frame.method === "runtime/initialized") {
        queueMicrotask(() => {
          this.dispatchEvent(
            new MessageEvent("message", {
              data: JSON.stringify({
                jsonrpc: "2.0",
                method: "runtime/status-changed",
                params: {
                  status: "ready",
                  serverInstanceId: options.serverInstanceId,
                  readyToken: frame.params.readyToken,
                  generation: options.readyGeneration,
                },
              }),
            }),
          );
          options.afterReady?.();
        });
      } else if (frame.method === "runtime/shutdown") {
        queueMicrotask(() => {
          const response =
            options.shutdownErrorCode === undefined
              ? {
                  jsonrpc: "2.0",
                  id: frame.id,
                  result: { accepted: true, status: "shutting_down" },
                }
              : {
                  jsonrpc: "2.0",
                  id: frame.id,
                  error: {
                    code: -32000,
                    message: "rejected",
                    data: { errorCode: options.shutdownErrorCode },
                  },
                };
          this.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(response) }));
        });
      }
    }

    /** 支持即时 close 或故意不确认 close，触发 runner 的 deadline 分支。 */
    close() {
      options.onCloseRequested?.();
      if (options.confirmClose === false) return;
      this.readyState = FakeRuntimeWebSocket.CLOSED;
      const event = new Event("close");
      Object.defineProperty(event, "code", { value: 1000 });
      this.dispatchEvent(event);
    }
  }
  Object.defineProperty(globalThis, "WebSocket", {
    configurable: true,
    writable: true,
    value: FakeRuntimeWebSocket,
  });
  try {
    return await exercise(sockets);
  } finally {
    if (previousDescriptor === undefined) delete globalThis.WebSocket;
    else Object.defineProperty(globalThis, "WebSocket", previousDescriptor);
  }
}

/** 关闭 RPC 必须使用 endpoint generation；ACK、WebSocket close 和协议错误保持独立证据。 */
test("requires the exact shutdown generation and separately reports WebSocket close failures", async () => {
  const endpoint = {
    host: "127.0.0.1",
    port: 49152,
    serverInstanceId: "srv_shutdown_fixture",
    runtimeGeneration: 4,
  };
  const token = "b".repeat(64);
  const makeAbort = (message) => {
    const controller = new AbortController();
    return { controller, abort: () => controller.abort(new Error(message)) };
  };

  const mismatch = makeAbort("generation mismatch fixture timeout");
  await withFakeRuntimeWebSocket(
    {
      serverInstanceId: endpoint.serverInstanceId,
      readyGeneration: endpoint.runtimeGeneration + 1,
      afterReady: () => setTimeout(mismatch.abort, 10),
    },
    async (activeSockets) => {
      await assert.rejects(
        requestRunScopedRuntimeShutdown(endpoint, token, mismatch.controller.signal),
        (error) => error.shutdownAcknowledged === false && error.socketClose?.status === "closed",
      );
      const socket = activeSockets[0];
      assert.ok(socket);
      assert.equal(
        socket.sent.some((frame) => frame.method === "runtime/shutdown"),
        false,
      );
    },
  );

  await withFakeRuntimeWebSocket(
    {
      serverInstanceId: endpoint.serverInstanceId,
      readyGeneration: endpoint.runtimeGeneration,
    },
    async (activeSockets) => {
      const result = await requestRunScopedRuntimeShutdown(endpoint, token);
      const socket = activeSockets[0];
      assert.ok(socket);
      assert.equal(result.readyGeneration, endpoint.runtimeGeneration);
      assert.equal(result.runtimeGeneration, endpoint.runtimeGeneration);
      assert.deepEqual(result.socketClose, { status: "closed", source: "close_event", code: 1000 });
      const shutdownFrame = socket.sent.find((frame) => frame.method === "runtime/shutdown");
      assert.ok(shutdownFrame);
      assert.deepEqual(shutdownFrame.params, {});
    },
  );

  const unconfirmedClose = makeAbort("close confirmation timeout");
  await withFakeRuntimeWebSocket(
    {
      serverInstanceId: endpoint.serverInstanceId,
      readyGeneration: endpoint.runtimeGeneration,
      confirmClose: false,
      onCloseRequested: unconfirmedClose.abort,
    },
    async () => {
      await assert.rejects(
        requestRunScopedRuntimeShutdown(endpoint, token, unconfirmedClose.controller.signal),
        (error) =>
          error.shutdownAcknowledged === true &&
          error.acknowledgement?.runtimeGeneration === endpoint.runtimeGeneration &&
          error.socketClose?.status === "failed" &&
          error.socketCloseFailure instanceof Error,
      );
    },
  );

  const protocolFailure = makeAbort("close after protocol rejection");
  await withFakeRuntimeWebSocket(
    {
      serverInstanceId: endpoint.serverInstanceId,
      readyGeneration: endpoint.runtimeGeneration,
      confirmClose: false,
      shutdownErrorCode: "shutdown_denied",
      onCloseRequested: protocolFailure.abort,
    },
    async () => {
      await assert.rejects(
        requestRunScopedRuntimeShutdown(endpoint, token, protocolFailure.controller.signal),
        (error) =>
          /runtime\/shutdown rejected: shutdown_denied/u.test(error.message) &&
          error.shutdownAcknowledged === false &&
          error.socketClose?.status === "failed" &&
          error.socketCloseFailure instanceof Error,
      );
    },
  );
});

/** Force 仅能绕过本轮隔离 cleanup 的 pending-work 拒绝，普通关闭和身份 fencing 保持原契约。
 * @author kongweiguang
 */
test("limits force shutdown to the verified isolated cleanup scope", async () => {
  const endpoint = {
    host: "127.0.0.1",
    port: 49152,
    serverInstanceId: "srv_force_fixture",
    runtimeGeneration: 7,
  };
  const token = "c".repeat(64);
  await assert.rejects(
    requestRunScopedRuntimeShutdown(endpoint, token, undefined, { force: true }),
    /verified isolated cleanup scope/u,
  );

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const protocolStart = source.indexOf("export async function requestRunScopedRuntimeShutdown(");
  const protocolEnd = source.indexOf("\n}\n", protocolStart);
  const cleanupStart = source.indexOf(
    "export async function shutdownVerifiedIsolatedRuntimeForCleanup(",
  );
  const cleanupEnd = source.indexOf("\n}\n", cleanupStart);
  const shutdownStart = source.indexOf("async function shutdownRunScopedRuntime(");
  const shutdownEnd = source.indexOf("\n}\n", shutdownStart);
  assert.ok(protocolStart >= 0 && protocolEnd > protocolStart);
  assert.ok(cleanupStart >= 0 && cleanupEnd > cleanupStart);
  assert.ok(shutdownStart >= 0 && shutdownEnd > shutdownStart);

  const protocol = source.slice(protocolStart, protocolEnd);
  const cleanup = source.slice(cleanupStart, cleanupEnd);
  const shutdown = source.slice(shutdownStart, shutdownEnd);
  assert.match(protocol, /forcePendingWork \? \{ force: true \} : \{\}/u);
  assert.match(cleanup, /force !== true/u);
  assert.match(cleanup, /expectedOwner\.creationDate/u);
  assert.match(cleanup, /frozenCommandLineHash/u);
  assert.match(cleanup, /freezeSharedRuntimeForCleanup/u);
  assert.match(cleanup, /scope: verifiedRuntimeCleanupForce/u);
  assert.doesNotMatch(cleanup, /\brm\s*\(/u);
  assert.doesNotMatch(cleanup, /DatabaseSync/u);
  assert.ok(
    shutdown.indexOf("identityVerification.completeIdentityMatches !== true") <
      shutdown.indexOf("await requestRunScopedRuntimeShutdown("),
  );
  assert.match(shutdown, /scope: verifiedRuntimeCleanupForce/u);
});

/** phase 级最终态必须消费 RPC ACK 与自然退出，且不能等窗口清理后再向已退出 owner 重发。 */
test("owned-tree shutdown acknowledgement is recorded before CDP disconnect and cleanup", async () => {
  const evidence = {
    first: {
      runtimeShutdown: {
        phase: "first",
        status: "passed",
        acknowledgement: { status: "acknowledged" },
        naturalExit: { status: "natural_exit" },
      },
    },
  };
  assert.equal(runtimeShutdownPhaseAcknowledged(evidence, "first"), true);
  assert.equal(runtimeShutdownPhaseAcknowledged(evidence, "second"), false);
  assert.equal(
    runtimeShutdownPhaseAcknowledged(
      {
        first: {
          runtimeShutdown: {
            ...evidence.first.runtimeShutdown,
            acknowledgement: undefined,
          },
        },
      },
      "first",
    ),
    false,
  );
  assert.equal(
    runtimeShutdownPhaseAcknowledged(
      {
        first: {
          runtimeShutdown: {
            ...evidence.first.runtimeShutdown,
            naturalExit: { status: "waiting" },
          },
        },
      },
      "first",
    ),
    false,
  );

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const ownedTreeShutdown = source.indexOf(
    'if (cleanupMode === "owned_tree" && finalRuntimeShutdownPhase)',
  );
  const shutdownCall = source.indexOf("await shutdownRunScopedRuntime(", ownedTreeShutdown);
  const browserDisconnect = source.indexOf(
    "await raceWithSignal(() => browser.close()",
    shutdownCall,
  );
  const phaseCleanup = source.indexOf(
    "const phaseCleanupFailure = await cleanupPhase(",
    browserDisconnect,
  );
  assert.ok(ownedTreeShutdown >= 0 && shutdownCall > ownedTreeShutdown);
  assert.ok(browserDisconnect > shutdownCall);
  assert.ok(phaseCleanup > browserDisconnect);

  const cleanupStart = source.indexOf("async function cleanupPhase(");
  const profilePrimeShutdown = source.indexOf('phase === "profilePrime"', cleanupStart);
  const profilePrimeProcessCleanup = source.indexOf("await stopProcessTree(", profilePrimeShutdown);
  assert.ok(cleanupStart >= 0 && profilePrimeShutdown > cleanupStart);
  assert.ok(profilePrimeProcessCleanup > profilePrimeShutdown);
  const profilePrimeShutdownSource = source.slice(profilePrimeShutdown, profilePrimeProcessCleanup);
  assert.match(
    profilePrimeShutdownSource,
    /const shutdownDirectories = Object\.create\(directories\)/u,
  );
  assert.match(
    profilePrimeShutdownSource,
    /shutdownDirectories\.sharedRuntimeFreeze = runtimeFreeze/u,
  );
  assert.match(profilePrimeShutdownSource, /await shutdownRunScopedRuntime\(/u);
  assert.match(
    profilePrimeShutdownSource,
    /runtimeShutdownPhaseAcknowledged\(phaseEvidence, phase\)/u,
  );
  assert.match(
    profilePrimeShutdownSource,
    /Object\.defineProperty\(directories, "sharedRuntimeIdentityHistory"/u,
  );
  const profilePrimeCall = source.indexOf("await primeWebViewProfile(");
  const formalLaunch = source.indexOf("const launch = startTauri(", profilePrimeCall);
  assert.ok(profilePrimeCall >= 0 && formalLaunch > profilePrimeCall);
});

/** @author kongweiguang 双重失败时保留 profile ACK 主因与 owner-verified cleanup 次因，避免后者覆盖根错误。 */
test("preserves the profile prime failure when owner-verified cleanup also fails", () => {
  const initializationFailure = new Error("WebView2 profile ACK timed out");
  const cleanupFailure = new Error("endpoint listener owner was not unique");

  const combined = combineProfilePrimeFailures(initializationFailure, cleanupFailure);

  assert.ok(combined instanceof AggregateError);
  assert.equal(combined.cause, initializationFailure);
  assert.deepEqual(combined.errors, [initializationFailure, cleanupFailure]);
  assert.equal(
    combineProfilePrimeFailures(initializationFailure, undefined),
    initializationFailure,
  );
  assert.equal(combineProfilePrimeFailures(undefined, cleanupFailure), cleanupFailure);
  assert.equal(combineProfilePrimeFailures(undefined, undefined), undefined);
});

/** @author kongweiguang 汇总和控制台使用同一脱敏递归投影，确保外层 AggregateError 不会吞掉 profile ACK 主因。 */
test("writes nested profile prime failures into the redacted run summary", async () => {
  const privateRoot = "C:\\runner\\private-profile-root";
  const initializationFailure = new Error(
    `WebView2 profile ACK timed out; lastObservation=browser=0/1 under ${privateRoot} api_key=init-secret`,
  );
  const cleanupFailure = new Error(
    `endpoint listener owner cleanup failed at ${privateRoot} token=cleanup-secret`,
  );
  const combined = combineProfilePrimeFailures(initializationFailure, cleanupFailure);
  const runId = `windows-profile-summary-${createHash("sha1")
    .update(randomBytes(16))
    .digest("hex")}`;
  let summaryPath;

  try {
    summaryPath = await writeRunSummary(
      runId,
      { phase: { status: "failed" } },
      "failed",
      combined,
      { root: privateRoot },
    );
    const summary = JSON.parse(await readFile(summaryPath, "utf8"));
    assert.equal(typeof summary.error, "string");
    assert.match(summary.error, /WebView2 profile ACK timed out/u);
    assert.match(summary.error, /lastObservation=browser=0\/1/u);
    assert.match(summary.error, /endpoint listener owner cleanup failed/u);
    assert.doesNotMatch(summary.error, /private-profile-root|init-secret|cleanup-secret/u);
  } finally {
    if (summaryPath !== undefined) await rm(summaryPath, { force: true });
  }

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /error=\$\{diagnostic\}/u);
  assert.match(source, /formatErrorDiagnostics\(reportError, directories\)/u);
});

/** @author kongweiguang 使用 Win32 真实短名与完整路径验证 profile 归属匹配，不模拟 native realpath 语义。 */
test("matches WebView2 profile paths across Windows 8.3 aliases", async (t) => {
  if (process.platform !== "win32") {
    t.skip("Windows short-path alias regression requires Win32");
    return;
  }

  const longPath = await mkdtemp(join(tmpdir(), "ja webview profile "));
  try {
    const powershell = String.raw`
$path = $env:JA_E2E_LONG_PATH
Add-Type -TypeDefinition @'
using System.Text;
using System.Runtime.InteropServices;
public static class JaProfileShortPathProbe {
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  public static extern uint GetShortPathName(string longPath, StringBuilder shortPath, uint bufferSize);
}
'@
$buffer = New-Object System.Text.StringBuilder 32768
$length = [JaProfileShortPathProbe]::GetShortPathName($path, $buffer, [uint32]$buffer.Capacity)
if ($length -eq 0 -or $length -ge $buffer.Capacity) { exit 2 }
$buffer.ToString()
`;
    const shortPath = execFileSync("pwsh", ["-NoProfile", "-Command", powershell], {
      encoding: "utf8",
      env: { ...process.env, JA_E2E_LONG_PATH: longPath },
    }).trim();
    if (shortPath.toLowerCase() === longPath.toLowerCase() || !shortPath.includes("~")) {
      t.skip("8.3 short paths are disabled for the temporary volume");
      return;
    }

    assert.equal(
      windowsPathMatches(`msedgewebview2.exe --user-data-dir=${longPath}`, shortPath),
      true,
    );
  } finally {
    await rm(longPath, { recursive: true, force: true });
  }
});

/** 进程祖先消失后仍保留已观察后代；部分 CIM 身份不能被当作自然退出证据。 */
test("retains observed daemon descendants and blocks natural exit on incomplete CIM identities", () => {
  const owner = {
    pid: 51_001,
    parentPid: 1,
    name: "java.exe",
    commandLine: "java -jar ja-app-server.jar",
    creationDate: "2026-10-05T10:00:00.000Z",
  };
  const child = {
    pid: 51_002,
    parentPid: owner.pid,
    name: "java.exe",
    commandLine: "java -Dja.worker=true",
    creationDate: "2026-10-05T10:00:01.000Z",
  };
  const freeze = {
    ownerIdentity: owner,
    identities: new Map([[owner.pid, owner]]),
  };
  const firstProbe = frozenRuntimeIdentities(freeze, [owner, child]);
  assert.deepEqual(firstProbe.map((entry) => entry.pid).sort(), [owner.pid, child.pid]);
  const orphanProbe = frozenRuntimeIdentities(freeze, [child]);
  assert.deepEqual(orphanProbe.map((entry) => entry.pid).sort(), [owner.pid, child.pid]);
  const orphanState = runtimeNaturalExitState(
    freeze,
    new Map(orphanProbe.map((entry) => [entry.pid, entry])),
    [child],
    { current: new Map(), dropped: 0 },
    undefined,
  );
  assert.equal(orphanState.status, "waiting");
  assert.equal(orphanState.liveProcessCount, 1);

  const partialChild = { ...child, commandLine: "" };
  const partialState = runtimeNaturalExitState(
    freeze,
    new Map([[child.pid, child]]),
    [owner, partialChild],
    { current: new Map([["partial-child", partialChild]]), dropped: 0 },
    undefined,
  );
  assert.equal(classifyProcessIdentityPresence(child, [partialChild]), "incomplete");
  assert.equal(partialState.status, "waiting");
  assert.equal(partialState.incompleteProcessCount, 1);
});

/** 通过 cleanup 生产 helper 记录跨快照与 phase 的身份，旧孤儿在自然退出确认前必须持续受保护。
 * @author kongweiguang
 */
test("records Prime descendants across phase freezes and waits for old orphans to exit", () => {
  const directories = {};
  const primeOwner = {
    pid: 61_001,
    parentPid: 1,
    name: "java.exe",
    commandLine: "java -Dphase=prime",
    creationDate: "2026-10-05T10:00:00.000Z",
  };
  const primeOrphan = {
    pid: 61_002,
    parentPid: primeOwner.pid,
    name: "java.exe",
    commandLine: "java -Dphase=prime-worker",
    creationDate: "2026-10-05T10:00:01.000Z",
  };
  const primeGrandchild = {
    ...primeOwner,
    pid: 61_003,
    parentPid: primeOrphan.pid,
    commandLine: "java -Dphase=prime-grandchild",
    creationDate: "2026-10-05T10:00:02.000Z",
  };
  const firstOwner = {
    ...primeOwner,
    pid: 61_004,
    commandLine: "java -Dphase=first",
    creationDate: "2026-10-05T10:00:03.000Z",
  };
  const primeFreeze = {
    ownerIdentity: primeOwner,
    identities: new Map([[primeOwner.pid, primeOwner]]),
  };
  assert.deepEqual(
    refreshRuntimeIdentityHistory(directories, primeFreeze, [primeOwner]).map((entry) => entry.pid),
    [primeOwner.pid],
  );
  const lateChildren = refreshRuntimeIdentityHistory(directories, primeFreeze, [
    primeOwner,
    primeOrphan,
    primeGrandchild,
  ]);
  assert.deepEqual(
    lateChildren.map((entry) => entry.pid).sort((left, right) => left - right),
    [primeOwner.pid, primeOrphan.pid, primeGrandchild.pid],
  );
  assert.equal(directories.sharedRuntimeIdentityHistory.size, 3);

  const afterPrimeOwnerExit = refreshRuntimeIdentityHistory(directories, primeFreeze, [
    primeOrphan,
    primeGrandchild,
  ]);
  assert.deepEqual(
    afterPrimeOwnerExit.map((entry) => entry.pid).sort((left, right) => left - right),
    [primeOwner.pid, primeOrphan.pid, primeGrandchild.pid],
  );
  const firstFreeze = {
    ownerIdentity: firstOwner,
    identities: new Map([[firstOwner.pid, firstOwner]]),
  };
  const runHistory = refreshRuntimeIdentityHistory(directories, firstFreeze, [firstOwner]);
  assert.equal(runHistory.length, 4);
  assert.equal(directories.sharedRuntimeIdentityHistory.size, 4);

  assert.throws(
    () =>
      refreshRuntimeIdentityHistory(directories, primeFreeze, [
        primeOwner,
        { ...primeOrphan, commandLine: "" },
        primeGrandchild,
      ]),
    /身份不完整/u,
  );
  assert.throws(
    () =>
      refreshRuntimeIdentityHistory(directories, primeFreeze, [{ ...primeOwner, commandLine: "" }]),
    /owner CIM 身份不完整/u,
  );
  assert.equal(directories.sharedRuntimeIdentityHistory.size, 4);

  const orphanState = runtimeNaturalExitState(
    firstFreeze,
    new Map(directories.sharedRuntimeIdentityHistory),
    [primeOrphan, primeGrandchild],
    { current: new Map(), dropped: 0 },
    undefined,
  );
  assert.equal(orphanState.status, "waiting");
  assert.equal(orphanState.liveProcessCount, 2);

  const exitState = runtimeNaturalExitState(
    firstFreeze,
    new Map(directories.sharedRuntimeIdentityHistory),
    [],
    { current: new Map(), dropped: 0 },
    undefined,
  );
  assert.equal(exitState.status, "natural_exit");
});

/** 使用两种真实屏幕比例与物理尺寸，防止 QA 再次把单台机器的分辨率当成产品约束。 */
test("accepts native maximized geometry at the actual monitor scale", () => {
  for (const native of [
    { maximized: true, innerSize: { width: 2560, height: 1392 }, scaleFactor: 1 },
    { maximized: true, innerSize: { width: 1920, height: 1040 }, scaleFactor: 1.25 },
    { maximized: true, innerSize: { width: 3840, height: 2088 }, scaleFactor: 1.5 },
  ]) {
    const metrics = {
      maximized: true,
      innerWidth: native.innerSize.width / native.scaleFactor,
      innerHeight: native.innerSize.height / native.scaleFactor,
      devicePixelRatio: native.scaleFactor,
    };
    assert.deepEqual(assertNativeViewportGeometry(metrics, native), {
      width: metrics.innerWidth,
      height: metrics.innerHeight,
      devicePixelRatio: native.scaleFactor,
    });
    assert.doesNotThrow(() => assertNativeViewportGeometry(metrics, native, native.scaleFactor));
  }
});

/** renderer 仿真、未最大化、窗口几何失效及显式 DPI 不符都必须阻止 native 截图结论。 */
test("rejects emulated or invalid geometry presented as a native maximized window", () => {
  const native = { maximized: true, innerSize: { width: 1920, height: 1040 }, scaleFactor: 1.25 };
  const metrics = { maximized: true, innerWidth: 1536, innerHeight: 832, devicePixelRatio: 1.25 };
  for (const [rendererPatch, nativePatch, requiredScale] of [
    [{ innerWidth: 1440 }, {}, null],
    [{ innerHeight: 900 }, {}, null],
    [{ devicePixelRatio: 1 }, {}, null],
    [{ maximized: false }, {}, null],
    [{}, { maximized: false }, null],
    [{}, { scaleFactor: Number.NaN }, null],
    [{}, { innerSize: { width: 0, height: 1040 } }, null],
    [{}, { innerSize: { width: 1920.5, height: 1040 } }, null],
    [{ innerWidth: 0 }, { innerSize: { width: 1, height: 1040 } }, null],
    [{}, {}, 1],
    [{}, {}, Number.NaN],
  ]) {
    assert.throws(
      () =>
        assertNativeViewportGeometry(
          { ...metrics, ...rendererPatch },
          { ...native, ...nativePatch },
          requiredScale,
        ),
      /native window and renderer geometry disagree/u,
    );
  }
});

/** 供真实 maximize helper 使用的合成诊断固定 HWND/PID、Win32 状态与 Tauri/renderer 几何契约。 */
function maximizedWindowEvidenceFixture() {
  const rectangle = {
    left: 0,
    top: 0,
    right: 2560,
    bottom: 1392,
    width: 2560,
    height: 1392,
  };
  const window = (patch) => ({
    hwnd: 9843557,
    processId: 416,
    isWindow: true,
    visible: true,
    title: "Ja",
    iconic: false,
    zoomed: true,
    windowRect: { ...rectangle },
    clientRect: { ...rectangle },
    ...patch,
  });
  const identity = {
    pid: 416,
    pidMatches: true,
    nameMatches: true,
    commandLineMatches: true,
    creationDateMatches: true,
    valid: true,
  };
  return {
    native: {
      pid: 416,
      action: "maximize",
      identityBefore: { ...identity },
      identityAfter: { ...identity },
      boundWindowHandleBefore: 9843557,
      boundWindowHandleAfter: 9843557,
      showWindowAsyncRequestAccepted: true,
      showWindowAsyncReturnSemantics: "request_accepted",
      before: window({ zoomed: false }),
      after: window({}),
    },
    renderer: {
      innerWidth: 2560,
      innerHeight: 1392,
      outerWidth: 2560,
      outerHeight: 1392,
      devicePixelRatio: 1,
      maximized: true,
    },
    tauri: {
      available: true,
      maximized: true,
      innerSize: { width: 2560, height: 1392 },
      scaleFactor: 1,
    },
  };
}

test("requires exact native maximize ownership and matching renderer/Tauri projection", () => {
  const evidence = maximizedWindowEvidenceFixture();
  assert.deepEqual(assertMaximizedWindowEvidence(evidence, 416), {
    width: 2560,
    height: 1392,
    devicePixelRatio: 1,
  });

  for (const invalidate of [
    (candidate) => {
      candidate.native.identityAfter.pid = 417;
    },
    (candidate) => {
      candidate.native.boundWindowHandleAfter += 1;
    },
    (candidate) => {
      candidate.native.after.zoomed = false;
    },
    (candidate) => {
      candidate.native.after.clientRect.width = 0;
    },
    (candidate) => {
      candidate.renderer.maximized = false;
    },
    (candidate) => {
      candidate.tauri.maximized = false;
    },
    (candidate) => {
      candidate.tauri.innerSize.width -= 100;
    },
  ]) {
    const invalid = maximizedWindowEvidenceFixture();
    invalidate(invalid);
    assert.throws(
      () => assertMaximizedWindowEvidence(invalid, 416),
      /owned native maximize evidence invalid|native window and renderer geometry disagree/u,
    );
  }
});

/** phase HWND 绑定忽略 MainWindowHandle 漂移，并在原 HWND 消失或 owner identity 改变时失败关闭。 */
test("keeps one phase HWND binding and rejects a vanished handle or changed process identity", () => {
  const identity = {
    pid: 416,
    parentPid: 400,
    name: "ja-desktop.exe",
    commandLine: "C:\\ja\\ja-desktop.exe --profile test",
    creationDate: "2026-10-05T00:00:00.000Z",
  };
  const scope = {};
  const binding = bindOwnedJaWindowHandle(scope, identity, 9843557);
  const restored = bindOwnedJaWindowHandle(scope, identity, 0);
  assert.equal(restored, binding);
  assert.equal(restored.hwnd, 9843557);

  const ownedIdentity = { ...identity, windowBinding: binding };
  assert.equal(
    assertOwnedJaWindowBinding(ownedIdentity, {
      hwnd: 9843557,
      isWindow: true,
      processId: 416,
      title: "Ja",
    }),
    9843557,
  );
  assert.throws(
    () =>
      assertOwnedJaWindowBinding(ownedIdentity, {
        hwnd: 9843557,
        isWindow: false,
        processId: 416,
        title: "Ja",
      }),
    /Ja HWND 绑定失效/u,
  );
  assert.throws(
    () =>
      assertOwnedJaWindowBinding(
        { ...ownedIdentity, pid: 417 },
        { hwnd: 9843557, isWindow: true, processId: 417, title: "Ja" },
      ),
    /Ja HWND 绑定失效/u,
  );
  assert.throws(
    () =>
      bindOwnedJaWindowHandle(
        scope,
        { ...identity, creationDate: "2026-10-05T00:00:01.000Z" },
        7711,
      ),
    /必须建立新的 phase scope/u,
  );
  assert.equal(bindOwnedJaWindowHandle({}, { ...identity, pid: 417 }, 7711).hwnd, 7711);
});

/** 三类原生消费者的外部 JSONL 回执都经生产校验器绑定到同一 phase HWND 和完整 live identity。 */
test("validates window action, SendInput, and Explorer postcheck receipts against one phase binding", () => {
  const identity = {
    pid: 416,
    parentPid: 400,
    name: "ja-desktop.exe",
    commandLine: "C:\\ja\\ja-desktop.exe --profile test",
    creationDate: "2026-10-05T00:00:00.000Z",
  };
  const binding = bindOwnedJaWindowHandle({}, identity, 9843557);
  const ownedIdentity = { ...identity, windowBinding: binding };
  const identityAfter = {
    pid: identity.pid,
    pidMatches: true,
    nameMatches: true,
    commandLineMatches: true,
    creationDateMatches: true,
    valid: true,
  };
  const receipts = [
    [
      "window action",
      { identityAfter, hwnd: binding.hwnd, processId: identity.pid, isWindow: true, title: "Ja" },
    ],
    [
      "SendInput",
      {
        identityAfter,
        ownedHandle: binding.hwnd,
        processId: identity.pid,
        isWindow: true,
        title: "Ja",
      },
    ],
    [
      "Explorer drag",
      { identityAfter, hwnd: binding.hwnd, processId: identity.pid, isWindow: true, title: "Ja" },
    ],
  ];

  for (const [consumer, receipt] of receipts) {
    const stdout = `${consumer} helper completed\n${JSON.stringify(receipt)}\n`;
    assert.deepEqual(readOwnedJaWindowPostcheck(ownedIdentity, stdout), receipt);

    for (const changedField of [
      "pidMatches",
      "nameMatches",
      "commandLineMatches",
      "creationDateMatches",
    ]) {
      const changedIdentity = structuredClone(receipt);
      changedIdentity.identityAfter[changedField] = false;
      assert.throws(
        () => readOwnedJaWindowPostcheck(ownedIdentity, JSON.stringify(changedIdentity)),
        /动作后进程身份复核失败/u,
        `${consumer} must reject changed ${changedField}`,
      );
    }

    const vanishedWindow = structuredClone(receipt);
    vanishedWindow.isWindow = false;
    assert.throws(
      () => readOwnedJaWindowPostcheck(ownedIdentity, JSON.stringify(vanishedWindow)),
      /Ja HWND 绑定失效/u,
      `${consumer} must reject its vanished HWND`,
    );
  }
});

/** 测试真实重试协调器的固定输入顺序，并证明第三次 deadline 仍失败且保留原始超时与成功输入回执。 */
test("orders each native shortcut retry and retains third condition-timeout evidence", async () => {
  const calls = [];
  const failures = [];
  const diagnostic = { activeElement: "xterm-helper-textarea", keyEvents: [] };
  let diagnosticCount = 0;
  let thirdTimeoutStack;

  await assert.rejects(
    runNativeShortcutEventAttempts({
      source: "xterm",
      command: "terminal",
      deadline: Date.now() + 10_000,
      focusWindow: async (attempt) => {
        calls.push(`${attempt}:window`);
        return { hwnd: 7001 };
      },
      prepareSurface: async (attempt) => {
        calls.push(`${attempt}:surface`);
        return { target: "xterm", ack: true };
      },
      resetModifiers: async (attempt) => {
        calls.push(`${attempt}:reset`);
        return { sent: 2 };
      },
      sendChord: async (attempt) => {
        calls.push(`${attempt}:chord`);
        return { sent: 3 };
      },
      waitForEvent: async (attempt) => {
        calls.push(`${attempt}:event`);
        const failure = new ConditionTimeoutError(`event ${attempt}`);
        failures.push(failure);
        if (attempt === 3) thirdTimeoutStack = failure.stack;
        throw failure;
      },
      captureDiagnostic: async () => {
        diagnosticCount += 1;
        return diagnostic;
      },
    }),
    (error) => {
      assert.match(error.message, /xterm terminal native event 超时/u);
      assert.equal(error.cause, failures[2]);
      assert.equal(error.cause.stack, thirdTimeoutStack);
      const evidence = JSON.parse(error.message.slice(error.message.indexOf("{")));
      assert.deepEqual(evidence.diagnostic, diagnostic);
      assert.deepEqual(
        evidence.attemptReceipts.map(({ attempt }) => attempt),
        [1, 2, 3],
      );
      assert.deepEqual(evidence.attemptReceipts[2], {
        attempt: 3,
        windowFocusReceipt: { hwnd: 7001 },
        surfacePreparationReceipt: { target: "xterm", ack: true },
        modifierResetReceipt: { sent: 2 },
        inputReceipt: { sent: 3 },
      });
      return true;
    },
  );

  assert.deepEqual(calls, [
    "1:window",
    "1:surface",
    "1:reset",
    "1:chord",
    "1:event",
    "2:window",
    "2:surface",
    "2:reset",
    "2:chord",
    "2:event",
    "3:window",
    "3:surface",
    "3:reset",
    "3:chord",
    "3:event",
  ]);
  assert.equal(diagnosticCount, 1);
});

/** post-send foreground/unknown-outcome 错误不得重放；abort 与桥接错误也原样失败。 */
test("propagates native shortcut input failures once without replaying side effects", async () => {
  for (const failurePhase of ["foreground", "unknown_outcome", "abort", "bridge"]) {
    const calls = [];
    let performCount = 0;
    let waitCount = 0;
    let diagnosticCount = 0;
    const controller = new AbortController();
    const sendOutcomeUnknown = ["foreground", "unknown_outcome"].includes(failurePhase);
    const originalCause = new Error(`${failurePhase} underlying failure`);
    const failure = new Error(
      failurePhase === "foreground"
        ? 'Win32 SendInput 身份或前台证据无效：{"foreground":false}'
        : `${failurePhase} failure`,
      { cause: originalCause },
    );
    const originalStack = failure.stack;
    await assert.rejects(
      runNativeShortcutEventAttempts({
        source: "preview",
        command: "preview",
        deadline: Date.now() + 10_000,
        signal: controller.signal,
        focusWindow: async () => {
          calls.push("window");
          return {};
        },
        prepareSurface: async () => {
          calls.push("surface");
          return {};
        },
        resetModifiers: async () => {
          calls.push("reset");
          return {};
        },
        sendChord: async () => {
          calls.push("chord");
          performCount += 1;
          if (sendOutcomeUnknown) throw failure;
          return {};
        },
        waitForEvent: async () => {
          calls.push("event");
          waitCount += 1;
          if (failurePhase === "abort") controller.abort(failure);
          throw failure;
        },
        captureDiagnostic: async () => {
          diagnosticCount += 1;
          return {};
        },
      }),
      (error) => {
        assert.equal(error, failure);
        assert.equal(error.cause, originalCause);
        assert.equal(error.stack, originalStack);
        return true;
      },
    );
    assert.deepEqual(
      calls,
      sendOutcomeUnknown
        ? ["window", "surface", "reset", "chord"]
        : ["window", "surface", "reset", "chord", "event"],
    );
    assert.equal(performCount, 1);
    assert.equal(waitCount, sendOutcomeUnknown ? 0 : 1);
    assert.equal(diagnosticCount, 0);
  }

  const controller = new AbortController();
  const beforeStartFailure = new Error("cancelled before start");
  controller.abort(beforeStartFailure);
  let started = false;
  await assert.rejects(
    runNativeShortcutEventAttempts({
      source: "main",
      command: "side_chat",
      deadline: Date.now() + 10_000,
      signal: controller.signal,
      focusWindow: async () => {
        started = true;
        return {};
      },
      prepareSurface: async () => ({}),
      resetModifiers: async () => ({}),
      sendChord: async () => ({}),
      waitForEvent: async () => {},
      captureDiagnostic: async () => ({}),
    }),
    (error) => error === beforeStartFailure,
  );
  assert.equal(started, false);
});

/** phase summary 附加诊断不能换掉最大化失败的原 Error 或其调用栈。 */
test("attaches maximize evidence without replacing the original exception stack", () => {
  const failure = new Error("original maximize failure");
  const originalStack = failure.stack;
  const evidence = maximizedWindowEvidenceFixture();
  assert.equal(attachMaximizedWindowEvidence(failure, evidence), failure);
  assert.equal(failure.message, "original maximize failure");
  assert.equal(failure.stack, originalStack);
  assert.deepEqual(failure.windowMaximizeEvidence, evidence);
});

/** 共用 fixture 让断言集中验证检查器的归属判断和问题选择。 */
const javaIssues = [{ path: "method", code: "invalid_value" }];

test("accepts a host status only after its Rust lifecycle parser validates it", () => {
  assert.deepEqual(
    summarizeTauriEventContractIssues("runtime/status-changed", javaIssues, {
      valid: true,
      issues: [],
    }),
    [],
  );
});

test("keeps malformed host status failures under the host schema", () => {
  const hostIssues = [{ path: "params.status", code: "invalid_value" }];
  assert.deepEqual(
    summarizeTauriEventContractIssues("runtime/status-changed", javaIssues, {
      valid: false,
      issues: hostIssues,
    }),
    hostIssues,
  );
});

test("never lets the host parser waive an invalid Java event", () => {
  assert.deepEqual(
    summarizeTauriEventContractIssues("turn/completed", javaIssues, {
      valid: true,
      issues: [],
    }),
    javaIssues,
  );
});

test("preserves empty issues for events accepted by the JA-RPC schema", () => {
  assert.deepEqual(summarizeTauriEventContractIssues("turn/completed", [], undefined), []);
});

/**
 * 重启必须按原身份点击可见历史行；不能把 session 列表首行、创建新 Thread 或原生 RPC 当作选择。
 * @author kongweiguang
 */
test("selects the persisted Task root through its identity-bound history row after restart", () => {
  assert.doesNotThrow(assertTaskThreadsRestartSelectionContract);
});

/** 锁定侧聊生命周期与 CAS 冲突后真实 Overview→详情的单次显式重确认顺序。 @author kongweiguang */
test("keeps side-chat lifecycles separate and CAS revalidation on visible task tabs", () => {
  assert.doesNotThrow(assertTaskThreadsContract);
});

/**
 * 正常窗口关闭证据容许进程在 commit RPC 回包前销毁 renderer，但必须有唯一 commit start、
 * 已 resolved listener-ready、完整自然退出和无故障 marker；任何负例都 fail closed。
 * @author kongweiguang
 */
test("derives Task scoped-close readiness only from the complete natural-close evidence", () => {
  const events = [
    { command: "ja_app_exit_listener_ready", phase: "start" },
    { command: "ja_app_exit_listener_ready", phase: "resolved" },
    { command: "ja_desktop_close_behavior_save", phase: "start" },
    { command: "ja_desktop_close_behavior_save", phase: "resolved" },
    { command: "ja_app_exit_commit", phase: "start" },
  ];
  const evidence = {
    events,
    closeStartIndex: 2,
    windowClosed: true,
    launcherExit: { code: 0, signal: null },
    nativeTree: {
      snapshotStatus: "fresh",
      live: [],
      incompleteLive: [],
      aborted: false,
    },
    exitTrace: {
      status: "complete",
      stages: ["exit_requested_enter", "exit_requested_return", "exit_enter", "exit_return"],
    },
    exitCleanupFailureMarkerObserved: false,
  };
  const proof = assertTaskThreadsNativeCloseEvidence(evidence);
  assert.equal(proof.status, "passed");
  assert.equal(proof.commitStartedCount, 1);
  assert.equal(proof.commitResolvedCount, 0);
  assert.equal(proof.commitCompletion, "page_closed_after_commit");
  assert.deepEqual(proof.nativeScopedCloseReadiness, {
    status: "derived_ready",
    directScopedCloseAckObserved: false,
    basis:
      "complete_exit_trace_natural_owned_tree_exit_fresh_empty_snapshot_no_bridge_failure_marker",
  });

  const resolvedCommit = assertTaskThreadsNativeCloseEvidence({
    ...evidence,
    events: [...events, { command: "ja_app_exit_commit", phase: "resolved" }],
  });
  assert.equal(resolvedCommit.commitCompletion, "resolved");
  for (const invalid of [
    { exitCleanupFailureMarkerObserved: true },
    { launcherExit: { code: 1, signal: null } },
    { nativeTree: { ...evidence.nativeTree, live: [{}] } },
    { exitTrace: { status: "partial", stages: ["exit_requested_enter"] } },
    {
      events: [
        ...events,
        { command: "ja_app_exit_cancel", phase: "start" },
        { command: "ja_app_exit_cancel", phase: "resolved" },
      ],
    },
    {
      events: [{ command: "ja_app_exit_listener_ready", phase: "start" }, ...events.slice(2)],
    },
    {
      events: [
        ...events.slice(0, 2),
        { command: "ja_app_exit_commit", phase: "start" },
        { command: "ja_desktop_close_behavior_save", phase: "start" },
        { command: "ja_desktop_close_behavior_save", phase: "resolved" },
      ],
    },
  ]) {
    assert.throws(
      () => assertTaskThreadsNativeCloseEvidence({ ...evidence, ...invalid }),
      /Task Native normal-close evidence incomplete/u,
    );
  }
});

/** Task 重启属于共享客户端生命周期，身份必须同时固定 serverInstanceId 与 generation。 @author kongweiguang */
test("rejects a Task restart that changes the shared App Server generation or identity", () => {
  const first = { status: "passed", serverInstanceId: "srv_shared", generation: 7 };
  assert.deepEqual(assertTaskThreadsSharedRuntimeIdentity(first, { ...first }), {
    status: "passed",
    sameBackend: true,
    serverInstanceId: "srv_shared",
    generation: 7,
  });
  assert.throws(
    () =>
      assertTaskThreadsSharedRuntimeIdentity(first, {
        ...first,
        serverInstanceId: "srv_restarted",
      }),
    /Task restart changed shared App Server identity/u,
  );
  assert.throws(
    () => assertTaskThreadsSharedRuntimeIdentity(first, { ...first, generation: 8 }),
    /Task restart changed shared App Server identity/u,
  );
});

/** @author kongweiguang 真正 commit 前需遵守生产 Files flush → Preview close → commit 顺序。 */
test("keeps the production Files and Preview flush boundary before Task exit commit", async () => {
  assert.deepEqual(await assertTaskThreadsExitControllerContract(), {
    status: "passed",
    sequence: "files_flush_then_preview_close_then_commit",
  });
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function requestTaskThreadsNormalExit(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const close = source.slice(start, end);
  assert.ok(
    close.indexOf('import("/src/api/tauri/closeBehavior.ts")') <
      close.indexOf('await saveCloseBehavior("exit")') &&
      close.indexOf('await saveCloseBehavior("exit")') <
        close.indexOf('invoke("plugin:window|close", { label: "main" })'),
  );
  assert.doesNotMatch(close, /invoke\("ja_desktop_close_behavior_save"/u);
  assert.doesNotMatch(close, /events\.push/u);
  assert.doesNotMatch(close, /ja_app_exit_(?:commit|cancel)/u);
  assert.match(close, /waitForEvent\("close"/u);
});

/** 关闭通知只接受当前 JA-RPC strict schema 的八字段回执，并输出闭集脱敏诊断。 @author kongweiguang */
test("requires all eight task/closed fields and rejects legacy, unknown, or mismatched receipts", () => {
  const expectedRoot = "thr_private_root";
  const expectedTarget = "thr_private_target";
  const receiptIds = ["thr_private_target", "thr_private_child"];
  const fields = [
    "closedThreadIds",
    "eventId",
    "generation",
    "occurredAt",
    "rootThreadId",
    "sequence",
    "serverInstanceId",
    "taskThreadId",
  ];
  const matched = taskClosedNotificationDiagnostics(
    {
      schemaValid: true,
      schemaIssueCount: 0,
      runtimeIdentityMatches: true,
      rootThreadId: expectedRoot,
      taskThreadId: expectedTarget,
      paramsFields: fields,
      fieldCount: 8,
      unknownCount: 0,
    },
    expectedRoot,
    expectedTarget,
    receiptIds,
    [...receiptIds].reverse(),
  );
  assert.deepEqual(matched, {
    schemaValid: true,
    schemaIssueCount: 0,
    runtimeIdentityMatches: true,
    fieldCount: 8,
    fieldsPresent: {
      closedThreadIds: true,
      eventId: true,
      generation: true,
      occurredAt: true,
      rootThreadId: true,
      sequence: true,
      serverInstanceId: true,
      taskThreadId: true,
    },
    paramsFields: fields,
    unknownCount: 0,
    rootMatches: true,
    targetMatches: true,
    normalizedSetMatches: true,
  });
  assert.equal(taskClosedNotificationMatchesContract(matched), true);

  const legacy = taskClosedNotificationDiagnostics(
    {
      schemaValid: false,
      schemaIssueCount: 5,
      runtimeIdentityMatches: false,
      rootThreadId: expectedRoot,
      taskThreadId: expectedTarget,
      paramsFields: ["closedThreadIds", "rootThreadId", "taskThreadId"],
      fieldCount: 3,
      unknownCount: 0,
    },
    expectedRoot,
    expectedTarget,
    receiptIds,
    receiptIds,
  );
  assert.equal(legacy.schemaValid, false);
  assert.equal(legacy.fieldCount, 3);
  assert.equal(legacy.fieldsPresent.eventId, false);
  assert.equal(legacy.fieldsPresent.generation, false);
  assert.equal(legacy.fieldsPresent.occurredAt, false);
  assert.equal(legacy.fieldsPresent.serverInstanceId, false);
  assert.equal(taskClosedNotificationMatchesContract(legacy), false);

  const unknown = taskClosedNotificationDiagnostics(
    {
      schemaValid: false,
      schemaIssueCount: 1,
      runtimeIdentityMatches: false,
      rootThreadId: expectedRoot,
      taskThreadId: expectedTarget,
      paramsFields: [...fields, "unexpectedPrivateField"],
      fieldCount: 9,
      unknownCount: 1,
    },
    expectedRoot,
    expectedTarget,
    receiptIds,
    receiptIds,
  );
  assert.equal(unknown.fieldCount, 9);
  assert.equal(unknown.unknownCount, 1);
  assert.equal(unknown.paramsFields.includes("unexpectedPrivateField"), false);
  assert.equal(taskClosedNotificationMatchesContract(unknown), false);

  const mismatched = taskClosedNotificationDiagnostics(
    {
      schemaValid: true,
      schemaIssueCount: 0,
      runtimeIdentityMatches: true,
      rootThreadId: "thr_other_root",
      taskThreadId: "thr_other_target",
      paramsFields: fields,
      fieldCount: 8,
      unknownCount: 0,
    },
    expectedRoot,
    expectedTarget,
    receiptIds,
    ["thr_private_target", "thr_different_child"],
  );
  assert.equal(mismatched.rootMatches, false);
  assert.equal(mismatched.targetMatches, false);
  assert.equal(mismatched.normalizedSetMatches, false);
  assert.equal(taskClosedNotificationMatchesContract(mismatched), false);

  const serialized = JSON.stringify({ legacy, unknown, mismatched });
  for (const privateValue of [
    expectedRoot,
    expectedTarget,
    "thr_other_root",
    "thr_other_target",
    "unexpectedPrivateField",
    "private-user-content",
    "server-instance-secret",
  ]) {
    assert.equal(serialized.includes(privateValue), false);
  }
});

/** 权威诊断只允许闭集阶段、布尔、revision、canonical state 与计数进入报告。 @author kongweiguang */
test("normalizes task cancellation authority diagnostics without identifiers or raw failures", () => {
  const valid = {
    importsStatus: "resolved",
    taskReadStatus: "resolved",
    taskListStatus: "resolved",
    rangeStatus: "resolved",
    resultStatus: "resolved",
    sameRoot: true,
    sameTarget: true,
    parentMatches: true,
    depthMatches: true,
    kindLifecycleMatches: true,
    lineageMatches: true,
    detailTaskMatchesTarget: true,
    detailThreadMatchesTarget: true,
    detailRootMatches: true,
    detailMatchesSummary: true,
    detailRevision: 4,
    listRevision: 4,
    detailState: "running",
    listState: "running",
    lifecycleMatches: true,
    cancelEligible: true,
    visibleStateAvailable: true,
    descendantCount: 2,
  };
  const stages = [
    ["imports", { ...valid, importsStatus: "rejected" }],
    ["task_read", { ...valid, taskReadStatus: "rejected" }],
    ["task_list", { ...valid, taskListStatus: "rejected" }],
    ["lineage", { ...valid, lineageMatches: false }],
    ["range", { ...valid, rangeStatus: "rejected" }],
    ["result", { ...valid, resultStatus: "rejected" }],
    ["none", valid],
  ];
  for (const [failurePhase, candidate] of stages) {
    assert.equal(
      normalizeTaskCancellationAuthorityDiagnostics(candidate).failurePhase,
      failurePhase,
    );
  }

  const diagnostic = normalizeTaskCancellationAuthorityDiagnostics({
    ...valid,
    detailRevision: -1,
    listRevision: Number.POSITIVE_INFINITY,
    detailState: "provider-secret-state",
    listState: "not-a-canonical-state",
    descendantCount: "private-count",
    rootThreadId: "thr_private_identity",
    threadId: "thr_private_identity",
    responseBody: "private-user-content",
    error: new Error("provider-private-stack"),
  });
  assert.deepEqual(diagnostic, {
    failurePhase: "range",
    importsStatus: "resolved",
    taskReadStatus: "resolved",
    taskListStatus: "resolved",
    rangeStatus: "resolved",
    resultStatus: "resolved",
    sameRoot: true,
    sameTarget: true,
    parentMatches: true,
    depthMatches: true,
    kindLifecycleMatches: true,
    lineageMatches: true,
    detailTaskMatchesTarget: true,
    detailThreadMatchesTarget: true,
    detailRootMatches: true,
    detailMatchesSummary: true,
    detailRevision: null,
    listRevision: null,
    revisionMatches: null,
    detailState: null,
    listState: null,
    stateMatches: null,
    lifecycleMatches: true,
    cancelEligible: true,
    visibleStateAvailable: true,
    descendantCount: null,
  });
  const serialized = JSON.stringify(diagnostic);
  for (const privateValue of [
    "thr_private_identity",
    "private-user-content",
    "provider-private-stack",
    "private-count",
  ]) {
    assert.equal(serialized.includes(privateValue), false);
  }
});

/** Overview 的可见状态节点必须精确匹配权威圆点与 taskElapsedLabel 的完整格式。 @author kongweiguang */
test("matches visible Overview task metadata against canonical authority state and elapsed text", () => {
  const matches = (metaText, overrides = {}) =>
    taskTreeMetaMatchesAuthority({
      visible: true,
      markerState: "running",
      authorityState: "running",
      stateLabel: "运行中",
      metaText,
      ...overrides,
    });

  for (const metaText of [
    "运行中",
    "运行中 · 0 秒",
    "运行中 · 59 秒",
    "运行中 · 1 分钟",
    "运行中 · 59 分钟",
    "运行中 · 1 小时 0 分钟",
    "运行中 · 4 小时 9 分钟",
  ]) {
    assert.equal(matches(metaText), true, metaText);
  }

  for (const metaText of [
    "前缀运行中",
    "运行中 · 60 秒",
    "运行中 · 0 分钟",
    "运行中 · 60 分钟",
    "运行中 · 1 小时 60 分钟",
    "运行中 · 01 秒",
    "运行中 · 1 分钟 任意文本",
    "运行中其它文本",
  ]) {
    assert.equal(matches(metaText), false, metaText);
  }
  assert.equal(matches("运行中", { visible: false }), false);
  assert.equal(matches("运行中", { markerState: "queued" }), false);
  assert.equal(matches("运行中", { authorityState: "unknown" }), false);
  assert.equal(matches("运行中", { stateLabel: "" }), false);
});

/** baseline 之后只等待本次调用的唯一 terminal phase，避免旧请求结果污染新取消动作。 @author kongweiguang */
test("classifies one Task invoke after its baseline and ignores older terminal phases", () => {
  const olderTrace = [
    { phase: "start" },
    { phase: "rejected", errorCode: "TASK_CONTEXT_REVISION_CONFLICT" },
  ];
  const baseline = olderTrace.length;
  assert.deepEqual(classifySingleTaskInvokeOutcome(olderTrace, baseline), { status: "pending" });
  assert.deepEqual(classifySingleTaskInvokeOutcome([...olderTrace, { phase: "start" }], baseline), {
    status: "pending",
  });
  assert.deepEqual(
    classifySingleTaskInvokeOutcome(
      [...olderTrace, { phase: "start" }, { phase: "resolved" }],
      baseline,
    ),
    { status: "resolved" },
  );
});

/** 只有已知 CAS 错误可原样报告；任意错误文本与未知 code 都折叠为稳定安全类别。 @author kongweiguang */
test("classifies safe Task invoke rejections without leaking raw error details", () => {
  assert.deepEqual(
    classifySingleTaskInvokeOutcome(
      [{ phase: "start" }, { phase: "rejected", errorCode: "TASK_CONTEXT_REVISION_CONFLICT" }],
      0,
    ),
    { status: "rejected", errorCode: "TASK_CONTEXT_REVISION_CONFLICT" },
  );
  const privateFailure = "provider-secret-and-user-content";
  const outcome = classifySingleTaskInvokeOutcome(
    [{ phase: "start" }, { phase: "rejected", errorCode: privateFailure }],
    0,
  );
  assert.deepEqual(outcome, { status: "rejected", errorCode: "TASK_INVOKE_REJECTED" });
  assert.equal(JSON.stringify(outcome).includes(privateFailure), false);
});

/** 一次用户操作只能有一个 start 与一个 terminal，丢失或重复阶段必须失败关闭。 @author kongweiguang */
test("rejects malformed or duplicate Task invoke lifecycle phases", () => {
  assert.deepEqual(classifySingleTaskInvokeOutcome([{ phase: "start" }, { phase: "start" }], 0), {
    status: "invalid",
    reason: "multiple_starts",
  });
  assert.deepEqual(classifySingleTaskInvokeOutcome([{ phase: "resolved" }], 0), {
    status: "invalid",
    reason: "terminal_without_start",
  });
  assert.deepEqual(
    classifySingleTaskInvokeOutcome([{ phase: "resolved" }, { phase: "start" }], 0),
    { status: "invalid", reason: "terminal_before_start" },
  );
  assert.deepEqual(
    classifySingleTaskInvokeOutcome(
      [{ phase: "start" }, { phase: "resolved" }, { phase: "rejected" }],
      0,
    ),
    { status: "invalid", reason: "multiple_terminal_phases" },
  );
  assert.deepEqual(classifySingleTaskInvokeOutcome([], 1), {
    status: "invalid",
    reason: "invalid_baseline",
  });
});

/** @author kongweiguang 刷新 trace 允许并发读取，但须全部闭合并拒绝错序与失败读取。 */
test("classifies only completed, non-rejected Task refresh batches after their baseline", () => {
  const older = [{ phase: "start" }, { phase: "resolved" }];
  assert.deepEqual(classifyTaskRefreshBatchOutcome(older, older.length), { status: "pending" });
  assert.deepEqual(classifyTaskRefreshBatchOutcome([...older, { phase: "start" }], 2), {
    status: "pending",
    startedCount: 1,
    resolvedCount: 0,
    pendingCount: 1,
  });
  assert.deepEqual(
    classifyTaskRefreshBatchOutcome([...older, { phase: "start" }, { phase: "resolved" }], 2),
    { status: "resolved", startedCount: 1, resolvedCount: 1, pendingCount: 0 },
  );
  assert.deepEqual(
    classifyTaskRefreshBatchOutcome(
      [{ phase: "start" }, { phase: "start" }, { phase: "resolved" }, { phase: "resolved" }],
      0,
    ),
    { status: "resolved", startedCount: 2, resolvedCount: 2, pendingCount: 0 },
  );
  assert.deepEqual(
    classifyTaskRefreshBatchOutcome(
      [{ phase: "start" }, { phase: "start" }, { phase: "resolved" }],
      0,
    ),
    { status: "pending", startedCount: 2, resolvedCount: 1, pendingCount: 1 },
  );
  assert.deepEqual(
    classifyTaskRefreshBatchOutcome(
      [{ phase: "start" }, { phase: "rejected", errorCode: "private detail" }],
      0,
    ),
    { status: "rejected" },
  );
  assert.deepEqual(
    classifyTaskRefreshBatchOutcome([{ phase: "resolved" }, { phase: "start" }], 0),
    { status: "invalid", reason: "terminal_without_start" },
  );
  assert.deepEqual(classifyTaskRefreshBatchOutcome([], 1), {
    status: "invalid",
    reason: "invalid_baseline",
  });
});

/** @author kongweiguang 取消前先闭合既有读取；挂起观察只能服从原全局 deadline/AbortSignal。 */
test("requires quiescent Task read traces before baselining and bounds the wait", async () => {
  assert.deepEqual(classifyTaskInvokeQuiescence([]), { status: "quiet", pendingCount: 0 });
  assert.deepEqual(classifyTaskInvokeQuiescence([{ phase: "start" }]), {
    status: "pending",
    pendingCount: 1,
  });
  assert.deepEqual(classifyTaskInvokeQuiescence([{ phase: "start" }, { phase: "rejected" }]), {
    status: "quiet",
    pendingCount: 0,
  });
  assert.deepEqual(classifyTaskInvokeQuiescence([{ phase: "resolved" }]), {
    status: "invalid",
    reason: "terminal_without_start",
  });

  let reads = 0;
  const quiet = await waitForTaskInvokeQuiescence(
    async () => ({
      read: reads++ === 0 ? [{ phase: "start" }] : [{ phase: "start" }, { phase: "resolved" }],
    }),
    ["read"],
    Date.now() + 1_000,
  );
  assert.deepEqual(quiet.read, { status: "quiet", pendingCount: 0 });

  const pendingStartedAt = Date.now();
  await assert.rejects(
    waitForTaskInvokeQuiescence(
      async () => ({ read: [{ phase: "start" }] }),
      ["read"],
      pendingStartedAt + 35,
    ),
    (error) => error instanceof ConditionTimeoutError,
  );

  const controller = new AbortController();
  const abortReason = new Error("caller cancelled refresh observation");
  const abortTimer = setTimeout(() => controller.abort(abortReason), 20);
  try {
    await assert.rejects(
      waitForTaskInvokeQuiescence(
        () => new Promise(() => {}),
        ["read"],
        Date.now() + 1_000,
        controller.signal,
      ),
      (error) => error === abortReason,
    );
  } finally {
    clearTimeout(abortTimer);
  }
});

/** @author kongweiguang 权威刷新三项必须都成功，任一拒绝立即停止且不会回退到自动取消重试。 */
test("waits for every authoritative Task refresh under the existing deadline", async () => {
  let reads = 0;
  const outcomes = await waitForTaskRefreshOutcomes(
    async () =>
      reads++ === 0
        ? {
            detail: [{ phase: "start" }],
            transcript: [{ phase: "start" }],
            rootList: [{ phase: "start" }],
          }
        : {
            detail: [{ phase: "start" }, { phase: "resolved" }],
            transcript: [{ phase: "start" }, { phase: "resolved" }],
            rootList: [{ phase: "start" }, { phase: "resolved" }],
          },
    { detail: 0, transcript: 0, rootList: 0 },
    Date.now() + 1_000,
  );
  assert.equal(reads, 2);
  assert.deepEqual(Object.keys(outcomes), ["detail", "transcript", "rootList"]);
  assert.ok(Object.values(outcomes).every((outcome) => outcome.status === "resolved"));

  await assert.rejects(
    waitForTaskRefreshOutcomes(
      async () => ({
        detail: [{ phase: "start" }, { phase: "resolved" }],
        transcript: [{ phase: "start" }, { phase: "rejected", errorCode: "private payload" }],
        rootList: [{ phase: "start" }, { phase: "resolved" }],
      }),
      { detail: 0, transcript: 0, rootList: 0 },
      Date.now() + 1_000,
    ),
    (error) => error.message === "Task transcript 权威刷新 invoke 被拒绝",
  );
});

/** @author kongweiguang 允许双调用仅限 CAS 明示、全权威读取对齐且确实由用户重确认一次。 */
test("counts exactly one normal cancel or one explicitly re-confirmed CAS retry", () => {
  assert.equal(expectedTaskCancelInvokeDelta({ status: "not_needed" }), 1);
  const explicitlyReconfirmed = {
    status: "explicitly_reconfirmed",
    conflictVisible: true,
    taskReadResolved: true,
    threadReadResolved: true,
    rootTaskListResolved: true,
    authoritativeLineageMatched: true,
    detailMatchedSummary: true,
    visibleStateMatched: true,
    secondConfirmationCount: 1,
    latestDescendantCount: 2,
  };
  assert.equal(expectedTaskCancelInvokeDelta(explicitlyReconfirmed), 2);
  assert.equal(
    expectedTaskCancelInvokeDelta({ ...explicitlyReconfirmed, rootTaskListResolved: false }),
    undefined,
  );
  assert.equal(
    expectedTaskCancelInvokeDelta({ ...explicitlyReconfirmed, secondConfirmationCount: 2 }),
    undefined,
  );
  assert.equal(
    expectedTaskCancelInvokeDelta({ ...explicitlyReconfirmed, status: "automatic_retry" }),
    undefined,
  );
});

/** terminal 成功或拒绝立即返回，不重发 invoke，也不把悬挂调用扩展到整轮 budget。 @author kongweiguang */
test("waits for one Task invoke outcome under the caller deadline", async () => {
  const snapshots = [[{ phase: "start" }], [{ phase: "start" }, { phase: "resolved" }]];
  let reads = 0;
  const resolved = await waitForTaskInvokeOutcome(
    async () => snapshots[Math.min(reads++, snapshots.length - 1)],
    0,
    Date.now() + 1_000,
  );
  assert.deepEqual(resolved, { status: "resolved" });
  assert.equal(reads, 2);

  let rejectedReads = 0;
  const rejected = await waitForTaskInvokeOutcome(
    async () => {
      rejectedReads += 1;
      return [
        { phase: "start" },
        { phase: "rejected", errorCode: "TASK_CONTEXT_REVISION_CONFLICT" },
      ];
    },
    0,
    Date.now() + 1_000,
  );
  assert.deepEqual(rejected, { status: "rejected", errorCode: "TASK_CONTEXT_REVISION_CONFLICT" });
  assert.equal(rejectedReads, 1);
});

/** pending 与悬挂 trace 都服从同一 caller deadline/abort；读取异常折叠为固定安全文案。 @author kongweiguang */
test("bounds pending and hanging Task invoke reads by the caller deadline and abort", async () => {
  const startedAt = Date.now();
  await assert.rejects(
    waitForTaskInvokeOutcome(async () => [{ phase: "start" }], 0, startedAt + 35),
    (error) => error instanceof ConditionTimeoutError,
  );

  const hangingReadStartedAt = Date.now();
  await assert.rejects(
    waitForTaskInvokeOutcome(() => new Promise(() => {}), 0, hangingReadStartedAt + 35),
    (error) => error instanceof ConditionTimeoutError,
  );

  const controller = new AbortController();
  const abortReason = new Error("caller cancelled the stage");
  const abortTimer = setTimeout(() => controller.abort(abortReason), 20);
  try {
    await assert.rejects(
      waitForTaskInvokeOutcome(
        () => new Promise(() => {}),
        0,
        Date.now() + 1_000,
        controller.signal,
      ),
      (error) => error === abortReason,
    );
  } finally {
    clearTimeout(abortTimer);
  }

  const privateTraceError = "private invoke payload and provider content";
  await assert.rejects(
    waitForTaskInvokeOutcome(
      () => Promise.reject(new Error(privateTraceError)),
      0,
      Date.now() + 1_000,
    ),
    (error) =>
      error.message === "Task read-only observation failed" &&
      !error.message.includes(privateTraceError),
  );
});

/** @author kongweiguang 保证创建后焦点先由产品自动到位，再单独验收显式聚焦样式。 */
test("checks side-chat Composer auto-focus before any test-driven focus", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runTaskThreadsAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const session = source.slice(start, end);
  const readiness = session.indexOf("const automaticFocusDeadline =");
  const automaticFocus = session.indexOf("globalThis.document.activeElement === textarea");
  const recordedAutoFocus = session.indexOf('recordStage?.("composer_auto_focus_verified"');
  const explicitFocus = session.indexOf("await taskInput.focus()");
  assert.ok(
    readiness >= 0 &&
      automaticFocus > readiness &&
      recordedAutoFocus > automaticFocus &&
      explicitFocus > recordedAutoFocus,
  );
  assert.match(session, /textarea\.isConnected[\s\S]*?!textarea\.disabled/u);
  assert.match(session, /documentHasFocus: globalThis\.document\.hasFocus\(\)/u);
  assert.match(session, /composerAutoFocus: automaticFocusEvidence/u);
  assert.match(session, /native-auto-focused\.png`,\s*true/u);
  assert.match(session, /focusedComposerEvidence\.inputBorderStyle !== "none"/u);
  assert.match(source, /evidence\.first\.taskComposerAutoFocus = \{/u);
});

/** 菜单取证先关闭并确认 owner 焦点再操作 chrome；侧聊清理保留 PTY，终端显式关闭仍等待真实 ACK。 */
test("keeps launcher dismissal and side-chat cleanup ordered before native capability actions", () => {
  assert.doesNotThrow(assertOuterTerminalCapabilityContract);
});

/** Busy 只产出诊断，accepted 才能消费原动作预算等待附件 ready。 */
test("waits for clipboard draft readiness only after an accepted receipt", async () => {
  const readyWaits = [];
  const captureDraftGeometry = async () => ({
    available: true,
    attachments: [],
  });
  await assert.rejects(
    awaitClipboardImportOutcome({
      receipt: { phase: "resolved", operationId: "clipboard-busy", outcome: "busy" },
      operationId: "clipboard-busy",
      expectedOutcome: "accepted",
      captureDraftGeometry,
      waitForAccepted: async () => readyWaits.push("busy"),
    }),
    (error) => {
      assert.match(error.message, /"stage":"resolved"/u);
      assert.match(error.message, /"outcome":"busy"/u);
      assert.match(error.message, /"operationId":"clipboard-busy"/u);
      assert.match(error.message, /"draftGeometry":\{"available":true/u);
      return true;
    },
  );
  assert.deepEqual(readyWaits, []);

  const acceptedReceipt = {
    phase: "resolved",
    operationId: "clipboard-accepted",
    outcome: "accepted",
  };
  assert.equal(
    await awaitClipboardImportOutcome({
      receipt: acceptedReceipt,
      operationId: acceptedReceipt.operationId,
      expectedOutcome: "accepted",
      captureDraftGeometry,
      waitForAccepted: async () => readyWaits.push("accepted"),
    }),
    acceptedReceipt,
  );
  assert.deepEqual(readyWaits, ["accepted"]);
});

/** 并发部分成功不能装成整体 ACK；仅失败 session 可重试，generation 与 owner 都不能被放宽。 */
test("requires every owned Terminal close ACK and retries only the failed session", () => {
  const failedSession = { sessionId: "current-one", generation: 7 };
  const succeededSession = { sessionId: "current-two", generation: 11 };
  const sessions = [failedSession, succeededSession];
  const partial = [
    { ...failedSession, phase: "start" },
    { ...succeededSession, phase: "start" },
    { ...succeededSession, phase: "resolved" },
    { ...failedSession, phase: "rejected" },
  ];
  const failedEvidence = terminalCloseEvidence(partial, sessions, { failedSession });
  assert.equal(failedEvidence.complete, true);
  assert.equal(failedEvidence.allAcknowledged, false);
  assert.equal(
    terminalCloseEvidence(partial.slice(0, 3), sessions, { failedSession }).complete,
    false,
  );
  assert.throws(() => terminalCloseEvidence(partial, sessions), /ACK 顺序错误/u);
  const retried = [
    ...partial,
    { ...failedSession, phase: "start" },
    { ...failedSession, phase: "resolved" },
  ];
  const completed = terminalCloseEvidence(retried, sessions, { failedSession, retry: true });
  assert.equal(completed.allAcknowledged, true);
  assert.deepEqual(
    completed.sessions.map(({ phases }) => phases),
    [
      ["start", "rejected", "start", "resolved"],
      ["start", "resolved"],
    ],
  );
  assert.throws(
    () =>
      terminalCloseEvidence([...retried, { ...succeededSession, phase: "start" }], sessions, {
        failedSession,
        retry: true,
      }),
    /已完成 session 被重放/u,
  );
  for (const foreign of [
    { sessionId: "retained-thread", generation: 7 },
    { ...failedSession, generation: 6 },
  ])
    assert.throws(
      () => terminalCloseEvidence([{ ...foreign, phase: "start" }], sessions),
      /owner 以外/u,
    );
  assert.throws(
    () => terminalCloseEvidence([{ ...succeededSession, phase: "resolved" }], sessions),
    /ACK 顺序错误/u,
  );
  assert.throws(() => terminalCloseEvidence([], [failedSession, failedSession]), /identity 重复/u);
  assert.equal(terminalCloseEvidence([], []).allAcknowledged, true);
});

/** 精确故障必须留给当前 session；后台 owner、旧 generation 与合法全局清理均继续透传。 */
test("binds the injected Terminal close failure to one real session generation", async () => {
  const dom = new JSDOM("<main></main>", { runScripts: "outside-only" });
  try {
    /** 在独立页面 realm 安装生产 probe，保持 addInitScript 的无 Node 闭包约束。 */
    const page = {
      evaluate: async (callback, argument) =>
        dom.window.Function("argument", `return (${callback.toString()})(argument);`)(argument),
    };
    const target = { sessionId: "current-session", generation: 9 };
    await injectNextTerminalCloseFailure(page, target);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    let delegated = 0;
    /** 只观察 probe 是否交回 native delegate；本单测不模拟产品关闭事务成功。 */
    const delegate = async () => {
      delegated += 1;
      return "delegate-ack";
    };
    const foreign = { sessionId: "retained-session", generation: 9 };
    const previous = { ...target, generation: 8 };
    for (const identity of [foreign, previous]) {
      assert.equal(
        await invoke({ command: "ja_terminal_close", args: { input: identity } }, delegate),
        "delegate-ack",
      );
      assert.deepEqual(
        JSON.parse(JSON.stringify(dom.window.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE__)),
        target,
      );
    }
    assert.equal(
      await invoke(
        { command: "ja_terminal_close_all", args: { workspaceId: "workspace" } },
        delegate,
      ),
      "delegate-ack",
    );
    assert.deepEqual(
      JSON.parse(JSON.stringify(dom.window.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE__)),
      target,
    );
    await assert.rejects(
      invoke({ command: "ja_terminal_close", args: { input: target } }, delegate),
      /JA_E2E_INJECTED_TERMINAL_CLOSE_FAILURE/u,
    );
    assert.equal(delegated, 3);
    assert.equal(dom.window.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE__, null);
    assert.equal(
      await invoke({ command: "ja_terminal_close", args: { input: target } }, delegate),
      "delegate-ack",
    );
    assert.equal(delegated, 4);
    const trace = JSON.parse(
      JSON.stringify(dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__.ja_terminal_close),
    );
    assert.deepEqual(
      trace.map(({ sessionId, generation, phase }) => ({ sessionId, generation, phase })),
      [
        { ...foreign, phase: "start" },
        { ...foreign, phase: "resolved" },
        { ...previous, phase: "start" },
        { ...previous, phase: "resolved" },
        { ...target, phase: "start" },
        { ...target, phase: "rejected" },
        { ...target, phase: "start" },
        { ...target, phase: "resolved" },
      ],
    );
    assert.deepEqual(
      Array.from(
        dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__.ja_terminal_close_all,
        ({ phase }) => phase,
      ),
      ["start", "resolved"],
    );
  } finally {
    dom.window.close();
  }
});

/** 独立浏览器 realm 执行真实投影与 probe；DOM 保留后台同名 Shell，不能由手工预过滤数组满足 owner 约束。 */
function nativeReviewFixture() {
  const dom = new JSDOM(
    `<main>
    <div data-thread-workbench-scope="ws-old:thr-old" hidden inert><aside class="ja-inspector" aria-label="工作区面板"><div data-tab-panel="review"><section data-ja-review-shell aria-label="审阅" data-source="commit"><p class="ja-review-empty is-error">旧错误</p></section></div></aside></div>
    <div data-thread-workbench-scope="ws-current:thr-current"><aside class="ja-inspector" aria-label="工作区面板"><div data-tab-panel="review"><section data-ja-review-shell aria-label="审阅" data-source="uncommitted">
      <button aria-label="重新读取审阅"></button><button class="ja-review-source-trigger" aria-label="审阅范围：未暂存"></button>
      <section data-ja-review-tree><button role="treeitem" data-review-file-id="file-current" data-review-layer="unstaged" aria-selected="true"><span class="ja-review-tree-file-name" title="sample.ts">sample.ts</span></button></section>
      <pre>SECRET_GIT_BODY</pre>
    </section></div></aside></div></main>`,
    { runScripts: "outside-only" },
  );
  const page = {
    /** 页面函数必须可序列化，不得偷偷引用 Node 闭包中的协议状态。 */
    evaluate: async (callback, argument) =>
      dom.window.Function("argument", `return (${callback.toString()})(argument);`)(argument),
  };
  return { dom, page };
}

/**
 * 执行真实 Diff 页面回调；JSDOM 只替身几何与 innerText，证明身份/ready 判定，不替代真窗验收。
 * 声明行数大于已渲染行数，保留生产虚拟化约束；plain 正文也应就绪，不等待高亮 worker。
 */
function reviewDiffFixture(viewMode = "split") {
  const { dom } = nativeReviewFixture();
  const reviewElement = dom.window.document.querySelector(
    '[data-thread-workbench-scope="ws-current:thr-current"] [data-ja-review-shell]',
  );
  reviewElement.insertAdjacentHTML(
    "beforeend",
    `
    <header class="ja-review-shell-header"></header>
    <div class="ja-review-diff" aria-label="变更 Diff" data-review-selected-file-id="file-current" data-review-selected-layer="unstaged">
      <section class="ja-review-unified-diff" data-review-unified-diff data-review-diff-mode="${viewMode}" data-review-diff-path="sample.ts" data-review-row-count="300" data-review-syntax="plain" aria-label="${viewMode === "split" ? "双栏" : "统一"} Diff sample.ts">
        <div class="ja-review-unified-diff-viewport"><div class="ja-review-unified-diff-spacer">
          <div class="ja-review-unified-diff-row" data-review-diff-row-kind="hunk"><code>SECRET_HUNK_BODY</code></div>
          <div class="ja-review-unified-diff-row" data-review-diff-row-kind="${viewMode === "split" ? "split-line" : "addition"}">${
            viewMode === "split"
              ? '<div class="ja-review-unified-diff-cell" data-review-diff-side="old" data-review-diff-cell-kind="deletion"><code>SECRET_OLD_BODY</code></div><div class="ja-review-unified-diff-cell" data-review-diff-side="new" data-review-diff-cell-kind="addition"><code>SECRET_GIT_BODY</code></div>'
              : "<code>SECRET_GIT_BODY</code>"
          }</div>
        </div></div>
      </section>
    </div>`,
  );
  /** 几何替身仅提供非零大小；祖先 hidden/CSS 仍由真实 callback 的可见性规则判定。 */
  dom.window.HTMLElement.prototype.getBoundingClientRect = function () {
    return { x: 0, y: 0, top: 0, left: 0, right: 100, bottom: 24, width: 100, height: 24 };
  };
  Object.defineProperty(dom.window.HTMLElement.prototype, "innerText", {
    /** JSDOM 无布局文本读取；用于验证 callback 自己剔除正文节点后的安全诊断。 */
    get() {
      return this.textContent;
    },
  });
  const review = {
    /** 在页面 realm 序列化实际 helper callback，不在 Node 侧重写 ready predicate。 */
    evaluate: async (callback, argument) =>
      dom.window.Function(
        "element",
        "argument",
        `return (${callback.toString()})(element, argument);`,
      )(reviewElement, argument),
  };
  return { dom, review, reviewElement };
}

/** 当前两种有界布局和真实 MergeView 文本回退都需内容就绪；诊断不得携带代码与 hunk 正文。 */
test("accepts current split and unified Review content and the live MergeView fallback", async () => {
  for (const viewMode of ["split", "unified"]) {
    const { dom, review, reviewElement } = reviewDiffFixture(viewMode);
    try {
      let evidence = await waitForReviewDiff(review, "sample.ts", Date.now() + 1_000);
      assert.equal(evidence.state, "success");
      assert.equal(evidence.selectionMatches, true);
      assert.equal(evidence.viewMode, viewMode);
      assert.equal(evidence.unifiedVisible, true);
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
      const bounded = reviewElement.querySelector(".ja-review-unified-diff");
      bounded.outerHTML =
        '<section class="ja-editor-diff" aria-label="只读 Diff sample.ts" data-file-path="sample.ts"><div class="cm-mergeView"><div class="cm-editor"><div class="cm-content"><div class="cm-line">SECRET_OLD_BODY</div></div></div><div class="cm-editor"><div class="cm-content"><div class="cm-line">SECRET_GIT_BODY</div></div></div></div></section>';
      evidence = await waitForReviewDiff(review, "sample.ts", Date.now() + 1_000);
      assert.equal(evidence.mergeVisible, true);
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    } finally {
      dom.window.close();
    }
  }
});

/** 错文件/布局、仅根或 hunk、隐藏/加载态均须继续等待；一次旧可见内容不能给新选中项背书。 */
test("rejects mismatched or unready current Review Diff content", async () => {
  const changes = [
    /** 同名标签不足以证明 path，必须读取当前渲染器真实属性。 */
    (root) =>
      root
        .querySelector(".ja-review-unified-diff")
        .setAttribute("data-review-diff-path", "other.ts"),
    /** 根仍关联上一文件时，即使代码可见也不能接受。 */
    (root) =>
      root
        .querySelector(".ja-review-diff")
        .setAttribute("data-review-selected-file-id", "other-file"),
    /** 正文模式与可访问标签不一致说明切换尚未提交。 */
    (root) =>
      root
        .querySelector(".ja-review-unified-diff")
        .setAttribute("data-review-diff-mode", "unified"),
    /** 空 viewport 不能凭声明行数冒充已物化内容。 */
    (root) => {
      root.querySelector(".ja-review-unified-diff-viewport").textContent = "";
    },
    /** hunk 头是导航信息，缺少实际代码行仍不算正文 ready。 */
    (root) => root.querySelector('[data-review-diff-row-kind="split-line"]').remove(),
    /** 虚拟化内容被隐藏时非零根几何仍不能放行。 */
    (root) => {
      root.querySelector(".ja-review-unified-diff-viewport").hidden = true;
    },
    /** 当前 loading 状态优先于残留正文。 */
    (root) =>
      root
        .querySelector(".ja-review-diff")
        .insertAdjacentHTML(
          "afterbegin",
          '<p class="ja-review-empty is-loading">正在读取审阅…</p>',
        ),
  ];
  for (const change of changes) {
    const { dom, review, reviewElement } = reviewDiffFixture();
    try {
      change(reviewElement);
      await assert.rejects(waitForReviewDiff(review, "sample.ts", Date.now() + 80), (error) => {
        assert.match(error.message, /审查 Diff 未达到可见安全渲染态/u);
        assert.equal(/SECRET_/u.test(error.message), false);
        return true;
      });
    } finally {
      dom.window.close();
    }
  }
});

/**
 * 只模拟本菜单 helper 的 Playwright 操作；点击时隐藏背景 ARIA，role locator 随即无法重解。
 * 实际 page callback 在 JSDOM realm 执行，held 元素与 Portal 都保留真实 DOM 身份及唯一性。
 */
function reviewPortalFixture() {
  const { dom, reviewElement } = reviewDiffFixture();
  const document = dom.window.document;
  reviewElement.insertAdjacentHTML(
    "beforeend",
    '<button id="current-trigger" aria-label="审查更多操作" aria-haspopup="menu" aria-expanded="false"></button>',
  );
  const triggerElement = document.getElementById("current-trigger");
  const host = reviewElement.closest("[data-thread-workbench-scope]");
  document.body.insertAdjacentHTML(
    "beforeend",
    '<div id="foreign-menu" role="menu" aria-label="审查更多操作菜单"></div>',
  );
  for (const element of document.querySelectorAll("*")) element.style.visibility = "visible";
  const state = { clicks: 0, disposed: 0, afterOpen: undefined, target: triggerElement };
  /** 命中替身仅支持本夹具的父子入口；布局验证仍执行真实页面回调。 */
  document.elementFromPoint = () => state.target;
  const page = {
    mouse: {
      /** 父子菜单都在命中验证后展开；子 Portal 在 body，不能凭 Host DOM 后代关系认领归属。 */
      click: async (x, y) => {
        assert.ok(Number.isFinite(x) && Number.isFinite(y));
        state.clicks += 1;
        host.setAttribute("aria-hidden", "true");
        const target = state.target;
        const isChild = target.id === "child-trigger";
        const popupId = isChild ? "child-menu" : "current-menu";
        if (isChild) target.closest('[role="menu"]').setAttribute("aria-hidden", "true");
        target.setAttribute("aria-expanded", "true");
        target.setAttribute("aria-controls", popupId);
        document.body.insertAdjacentHTML(
          "beforeend",
          `<div id="${popupId}" role="menu" aria-labelledby="${target.id}" aria-label="审查更多操作菜单">${isChild ? "" : '<div id="child-trigger" role="menuitem" aria-label="未提交" aria-haspopup="menu" aria-expanded="false" style="visibility:visible"></div>'}</div>`,
        );
        state.afterOpen?.();
      },
    },
    /** 解析 ElementHandle 参数后执行 helper 本身的展开/controls/owner ACK，不能预先替它判定成功。 */
    waitForFunction: async (callback, argument, options) => {
      assert.ok(options.timeout > 0);
      const value = dom.window.Function(
        "argument",
        `return (${callback.toString()})(argument);`,
      )({ ...argument, current: argument.current.node });
      if (!value) throw new Error("fixture Portal ACK pending");
      return {
        /** JSHandle 只返回 callback 的实际结果，防止测试自己补正确 Portal id。 */
        jsonValue: async () => value,
        /** 记录资源释放，确保 helper 返回后不会保留临时 ACK handle。 */
        dispose: async () => {
          state.disposed += 1;
        },
      };
    },
    /** 返回受控 Portal 的真实 CSS 定位；同名 foreign menu 不能通过 id 绑定。 */
    locator: (selector) => ({
      /** 唯一性由实际 DOM 数量决定。 */
      count: async () => document.querySelectorAll(selector).length,
      /** 可见等待只接受唯一已附着且未隐藏的当前 Portal。 */
      waitFor: async ({ state: expected, timeout }) => {
        assert.equal(expected, "visible");
        assert.ok(timeout > 0);
        const elements = document.querySelectorAll(selector);
        assert.equal(elements.length, 1);
        assert.equal(elements[0].closest('[hidden], [aria-hidden="true"]'), null);
      },
      /** 回读用于断言 helper 绑定的实际 id。 */
      getAttribute: async (name) => document.querySelector(selector).getAttribute(name),
      /** 本夹具只支持当前父菜单的一个子入口，且必须通过实际 Portal 范围定位。 */
      getByRole: (role, { name, exact }) => {
        assert.equal(role, "menuitem");
        assert.equal(name, "未提交");
        assert.equal(exact, true);
        const roots = document.querySelectorAll(selector);
        assert.equal(roots.length, 1);
        return roleTrigger(roots[0], '[role="menuitem"][aria-label="未提交"]');
      },
    }),
  };
  /** 父入口与子入口共用有限 Locator 操作，但各自冻结查询根，避免父对象被切换为子对象。 */
  function roleTrigger(root, selector) {
    /** 原 role locator 每次真实查 DOM；背景 aria-hidden 后不返回缓存节点。 */
    const matches = () =>
      [...root.querySelectorAll(selector)].filter(
        (element) => element.closest('[aria-hidden="true"], [hidden], [inert]') === null,
      );
    /** 严格单元素解引用，不提供 first/nth 或隐藏入口回退。 */
    const current = () => {
      const elements = matches();
      assert.equal(elements.length, 1, "background role locator unavailable");
      return elements[0];
    };
    return {
      /** 页面对象保持独立于 role locator 是否仍可重解。 */
      page: () => page,
      /** count 模拟 accessibility tree 随 modal 改变。 */
      count: async () => matches().length,
      /** 点击前属性读取可用，点击后必须暴露旧路径真实失败。 */
      getAttribute: async (name) => current().getAttribute(name),
      /** held 元素只在点击前取得；后续 evaluate 不按 role 再查 DOM。 */
      elementHandle: async ({ timeout }) => {
        assert.ok(timeout > 0);
        const node = current();
        return {
          node,
          /** 在浏览器 realm 执行真实绑定回调，允许后续区分脱离 DOM 与 ARIA 背景隐藏。 */
          evaluate: async (callback, argument) =>
            dom.window.Function(
              "element",
              "argument",
              `return (${callback.toString()})(element, argument);`,
            )(node, argument),
          /** held 元素完成使命后应释放，失败路径亦须执行。 */
          dispose: async () => {
            state.disposed += 1;
          },
        };
      },
      /** 点击准备仍通过原 role 定位，禁止绕过已验证可用的用户入口。 */
      waitFor: async ({ state: expected, timeout }) => {
        assert.equal(expected, "attached");
        assert.ok(timeout > 0);
        current();
      },
      /** 模拟真实控件启用状态。 */
      isEnabled: async () => !current().disabled,
      /** 点击前滚动冻结当前命中目标，不能在父子入口间沿用上一次 pointer 节点。 */
      scrollIntoViewIfNeeded: async () => {
        state.target = current();
      },
      /** 命中检查也执行真实 helper callback，不把几何判断简化为常量 ready。 */
      evaluate: async (callback) =>
        dom.window.Function("element", `return (${callback.toString()})(element);`)(current()),
    };
  }
  const trigger = roleTrigger(reviewElement, 'button[aria-label="审查更多操作"]');
  return { dom, trigger, triggerElement, host, state };
}

/** 菜单打开后原 role 定位不可达，但已验证的同一元素仍能给出唯一 controls ACK。 */
test("binds Review Portal after modal ARIA hides the original role locator", async () => {
  const { dom, trigger, state } = reviewPortalFixture();
  try {
    const { menu } = await openReviewPortalMenu(trigger, Date.now() + 1_000);
    assert.equal(await menu.getAttribute("id"), "current-menu");
    assert.equal(state.clicks, 1);
    assert.equal(state.disposed, 2);
    assert.equal(await trigger.count(), 0);
    await assert.rejects(
      trigger.getAttribute("aria-controls"),
      /background role locator unavailable/u,
    );
  } finally {
    dom.window.close();
  }
});

/** 合法子菜单渲染在 body；必须继承父 scope，不能要求它是活动 Host 的 DOM 后代。 */
test("inherits the frozen Review owner for a normal Portal submenu", async () => {
  const { dom, trigger, state } = reviewPortalFixture();
  try {
    const parent = await openReviewPortalMenu(trigger, Date.now() + 1_000);
    const childTrigger = parent.menu.getByRole("menuitem", { name: "未提交", exact: true });
    assert.equal(
      dom.window.document.getElementById("child-trigger").closest("[data-thread-workbench-scope]"),
      null,
    );
    const child = await openReviewPortalMenu(
      childTrigger,
      Date.now() + 1_000,
      undefined,
      parent.ownerScope,
    );
    assert.equal(await child.menu.getAttribute("id"), "child-menu");
    assert.equal(child.ownerScope, "ws-current:thr-current");
    assert.equal(child.ownerScope, parent.ownerScope);
    assert.equal(state.clicks, 2);
    assert.equal(state.disposed, 4);
  } finally {
    dom.window.close();
  }
});

/** 父菜单返回后即使旧 Portal 仍可访问，新的活动 Thread 也不能被子调用重新认领。 */
test("rejects a Review owner switch between parent and submenu calls", async () => {
  const { dom, trigger, host, state } = reviewPortalFixture();
  try {
    const parent = await openReviewPortalMenu(trigger, Date.now() + 1_000);
    host.hidden = true;
    const nextHost = dom.window.document.querySelector(
      '[data-thread-workbench-scope="ws-old:thr-old"]',
    );
    nextHost.removeAttribute("hidden");
    nextHost.removeAttribute("inert");
    const childTrigger = parent.menu.getByRole("menuitem", { name: "未提交", exact: true });
    assert.equal(await childTrigger.count(), 1);
    await assert.rejects(
      openReviewPortalMenu(childTrigger, Date.now() + 1_000, undefined, parent.ownerScope),
      /Review 菜单入口所属 Thread 已变化/u,
    );
    assert.equal(state.clicks, 1);
    assert.equal(state.disposed, 3);
    assert.equal(dom.window.document.getElementById("child-menu"), null);
  } finally {
    dom.window.close();
  }
});

/** held 元素脱离/替换或活动 Thread 改变必须失败，不能按旧 id 寻找替代入口继续动作。 */
test("rejects detached Review menu triggers and changed owners after opening", async () => {
  for (const replace of [false, true]) {
    const { dom, trigger, triggerElement, host, state } = reviewPortalFixture();
    try {
      /** 两个变体分别保留相同 id 的替代节点及保留原节点的失活 Host，区分身份与生命周期。 */
      state.afterOpen = () => {
        if (replace) triggerElement.replaceWith(triggerElement.cloneNode(true));
        else host.hidden = true;
      };
      await assert.rejects(
        openReviewPortalMenu(trigger, Date.now() + 1_000),
        /Review 菜单入口已脱离原 DOM|Review 菜单入口所属 Thread 已变化/u,
      );
      assert.equal(state.clicks, 1);
      assert.equal(state.disposed, 1);
    } finally {
      dom.window.close();
    }
  }
});

/**
 * 只控制 RAF 时序与真实 DOM 几何，执行实际页面闭环 callback；ARIA/CSS/持久值先于旧几何更新。
 * 资源计数透明委托 JSDOM timer/event，实现成功、超时和父取消的清理验证，不替代真窗布局证据。
 */
function joinedLayoutFixture() {
  const dom = new JSDOM(
    '<div id="ja-workspace-layout" style="display:grid;column-gap:0px;grid-template-columns:592.75px 407.25px;--ja-workbench-size:40.725%;padding:0;border:0"><div id="conversation"></div><div id="workbench"></div><div id="handle" role="separator" aria-label="调整工作台宽度" aria-valuenow="40.725"></div></div>',
    { runScripts: "outside-only", url: "https://ja.test" },
  );
  const layout = dom.window.document.getElementById("ja-workspace-layout");
  dom.window.localStorage.setItem(
    "ja-ui-preferences-v1",
    JSON.stringify({ version: 1, state: { workbenchSize: 40.725 } }),
  );
  const state = {
    ratio: 40.225,
    clock: 0,
    nextFrame: 0,
    frames: new Map(),
    timers: new Set(),
    listeners: new Set(),
    closed: false,
  };
  /** 与生产使用相同 container 分母及零 gap；只有 state.ratio 控制实际布局，ARIA 不影响矩形。 */
  dom.window.HTMLElement.prototype.getBoundingClientRect = function () {
    const boundary = 1_000 * (1 - state.ratio / 100);
    const left = this.id === "workbench" ? boundary : this.id === "handle" ? boundary - 5.5 : 0;
    const width =
      this.id === "workbench"
        ? 1_000 - boundary
        : this.id === "conversation"
          ? boundary
          : this.id === "handle"
            ? 11
            : 1_000;
    return { x: left, y: 0, width, height: 600, top: 0, left, right: left + width, bottom: 600 };
  };
  /** 每次回调必须等下一次手动 RAF，不能在同一帧重复采样凑稳定计数。 */
  dom.window.requestAnimationFrame = (callback) => {
    const id = ++state.nextFrame;
    state.frames.set(id, callback);
    return id;
  };
  /** 透明记录真正取消的待执行帧。 */
  dom.window.cancelAnimationFrame = (id) => {
    state.frames.delete(id);
  };
  const setTimer = dom.window.setTimeout.bind(dom.window);
  const clearTimer = dom.window.clearTimeout.bind(dom.window);
  /** 超时由真实 timer 触发，回归不能直接补 timeout 状态。 */
  dom.window.setTimeout = (callback, duration) => {
    const id = setTimer(() => {
      state.timers.delete(id);
      callback();
    }, duration);
    state.timers.add(id);
    return id;
  };
  /** 释放统计必须委托真实 clearTimeout，不能只清空测试集合。 */
  dom.window.clearTimeout = (id) => {
    state.timers.delete(id);
    clearTimer(id);
  };
  const addListener = layout.addEventListener.bind(layout);
  const removeListener = layout.removeEventListener.bind(layout);
  /** 记录本闭环的局部停止监听器，事件投递仍由 DOM 实现。 */
  layout.addEventListener = (event, callback, options) => {
    state.listeners.add(callback);
    addListener(event, callback, options);
  };
  /** 记录 cleanup 的真实 listener 释放。 */
  layout.removeEventListener = (event, callback, options) => {
    state.listeners.delete(callback);
    removeListener(event, callback, options);
  };
  const workspaceLayout = {
    /** 页面 callback 必须在浏览器 realm 执行；关闭页面时真实失败，不构造缺失矩形。 */
    evaluate: async (callback, argument) => {
      if (state.closed) throw new Error("fixture page closed");
      return dom.window.Function(
        "layout",
        "argument",
        `return (${callback.toString()})(layout, argument);`,
      )(layout, argument);
    },
  };
  /** 新帧只消费本次开始前排队的回调；下一 RAF 保留给后续帧，微任务只用于读取是否已完成。 */
  const advanceFrame = async () => {
    state.clock += 16;
    const callbacks = [...state.frames.values()];
    state.frames.clear();
    for (const callback of callbacks) callback(state.clock);
    await Promise.resolve();
    await Promise.resolve();
  };
  return { dom, workspaceLayout, state, advanceFrame };
}

/** ARIA 及持久值先更新不能放行；真实比例达到原容差后，还要两个独立稳定 RAF 帧。 */
test("waits for actual joined layout convergence after an early ARIA acknowledgement", async () => {
  const { dom, workspaceLayout, state, advanceFrame } = joinedLayoutFixture();
  try {
    let settled = false;
    const pending = waitForJoinedInspectorLayoutSnapshot(
      workspaceLayout,
      40.725,
      Date.now() + 2_000,
    );
    pending.then(
      () => {
        settled = true;
      },
      () => {
        settled = true;
      },
    );
    await advanceFrame();
    await advanceFrame();
    assert.equal(settled, false);
    state.ratio = 40.725;
    await advanceFrame();
    assert.equal(settled, false);
    await advanceFrame();
    const snapshot = await pending;
    assert.ok(Math.abs(snapshot.workbenchRatio - 40.725) <= 0.1);
    assert.equal(snapshot.gapPx, 0);
    assert.equal(snapshot.persistedSize, 40.725);
    assert.equal(snapshot.pixels.container.width, 1_000);
    assert.equal(snapshot.pixels.handle.width, 11);
    assert.equal(snapshot.computedGridColumns, "592.75px 407.25px");
    assert.equal(state.frames.size, 0);
    assert.equal(state.timers.size, 0);
    assert.equal(state.listeners.size, 0);
  } finally {
    dom.window.close();
  }
});

/** 长期稳定的旧比例仍须超时失败，并保留实际像素/CSS；不能将 stable 与达到目标混为一谈。 */
test("times out with actual joined layout diagnostics when geometry never reaches the target", async () => {
  const { dom, workspaceLayout, state, advanceFrame } = joinedLayoutFixture();
  try {
    const pending = waitForJoinedInspectorLayoutSnapshot(workspaceLayout, 40.725, Date.now() + 60);
    const rejected = assert.rejects(pending, (error) => {
      const diagnostic = JSON.parse(error.message.slice(error.message.indexOf("{")));
      assert.equal(diagnostic.status, "timeout");
      assert.equal(diagnostic.stableFrames, 0);
      assert.equal(diagnostic.snapshot.separatorValue, 40.725);
      assert.ok(Math.abs(diagnostic.snapshot.workbenchRatio - 40.225) < 0.001);
      assert.equal(diagnostic.snapshot.pixels.container.width, 1_000);
      assert.equal(diagnostic.snapshot.pixels.handle.width, 11);
      assert.equal(diagnostic.snapshot.workbenchSizeCss, "40.725%");
      return true;
    });
    await advanceFrame();
    await advanceFrame();
    await rejected;
    assert.equal(state.frames.size, 0);
    assert.equal(state.timers.size, 0);
    assert.equal(state.listeners.size, 0);
  } finally {
    dom.window.close();
  }
});

/** 父取消要结束局部 RAF/timer 并拒绝；页面消失时保留 unknown，不能返回零尺寸或假成功。 */
test("fails and cleans up joined layout observation on cancellation or a missing page", async () => {
  for (const closed of [false, true]) {
    const { dom, workspaceLayout, state, advanceFrame } = joinedLayoutFixture();
    try {
      state.closed = closed;
      const controller = new AbortController();
      const pending = waitForJoinedInspectorLayoutSnapshot(
        workspaceLayout,
        40.725,
        Date.now() + 2_000,
        controller.signal,
      );
      const rejected = assert.rejects(pending, (error) => {
        const diagnostic = JSON.parse(error.message.slice(error.message.indexOf("{")));
        if (closed) {
          assert.equal(diagnostic.status, "unavailable");
          assert.equal(diagnostic.snapshot, null);
          assert.equal(diagnostic.cleanupStatus, "unavailable");
        } else {
          assert.equal(diagnostic.status, "cancelled");
          assert.equal(error.cause.message, "layout parent cancelled");
        }
        return true;
      });
      if (!closed) {
        await advanceFrame();
        controller.abort(new Error("layout parent cancelled"));
      }
      await rejected;
      assert.equal(state.frames.size, 0);
      assert.equal(state.timers.size, 0);
      assert.equal(state.listeners.size, 0);
    } finally {
      dom.window.close();
    }
  }
});

/** 页面观察及清理 evaluate 都不返回时，真实 Node deadline 仍必须拒绝，并释放自身 timer。 */
test("bounds a stalled joined layout page promise with the Node deadline", async (context) => {
  const liveTimers = new Set();
  const setTimer = globalThis.setTimeout;
  const clearTimer = globalThis.clearTimeout;
  /** 透明委托真实 Node timer，不用假时钟或页面 timeout 模拟阶段截止。 */
  context.mock.method(globalThis, "setTimeout", (callback, duration, ...arguments_) => {
    const timer = setTimer(
      (...values) => {
        liveTimers.delete(timer);
        callback(...values);
      },
      duration,
      ...arguments_,
    );
    liveTimers.add(timer);
    return timer;
  });
  /** finally 的释放必须调用真实 clearTimeout，不能只丢弃诊断句柄。 */
  context.mock.method(globalThis, "clearTimeout", (timer) => {
    liveTimers.delete(timer);
    clearTimer(timer);
  });
  const parent = new AbortController();
  let evaluations = 0;
  let cleanupStarted;
  const workspaceLayout = {
    /** 页面 Promise 与 cleanup 都真实保持 pending；成功、像素和资源状态一律不可构造。 */
    evaluate: async (_callback, input, options) => {
      evaluations += 1;
      assert.ok(options.timeout > 0);
      if (evaluations === 2) {
        cleanupStarted = Date.now();
        assert.equal(input.event, "ja-e2e-joined-layout-stop");
        assert.equal(input.status, "timeout");
      }
      return new Promise(() => {});
    },
  };
  const started = Date.now();
  await assert.rejects(
    waitForJoinedInspectorLayoutSnapshot(workspaceLayout, 40.725, started + 40, parent.signal),
    (error) => {
      const diagnostic = JSON.parse(error.message.slice(error.message.indexOf("{")));
      assert.match(error.cause.message, /工作台最终布局 超过 \d+ms/u);
      assert.equal(diagnostic.status, "unavailable");
      assert.equal(diagnostic.snapshot, null);
      assert.equal(diagnostic.cleanupStatus, "unavailable");
      return true;
    },
  );
  assert.equal(parent.signal.aborted, false);
  assert.equal(evaluations, 2);
  assert.ok(cleanupStarted - started < 1_000);
  assert.ok(Date.now() - started < 5_000);
  assert.equal(liveTimers.size, 0);
});

/** 后台 Shell 不参与当前 owner 的错误/来源判定；缺失 DOM 与未知 native 计数不能冒充 Rust 拒绝或零调用。 */
test("distinguishes the current Review owner, missing DOM and unavailable native observation", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    let evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.ownerCount, 1);
    assert.equal(evidence.ownerScope, "ws-current:thr-current");
    assert.equal(evidence.sourceKind, "uncommitted");
    assert.equal(evidence.fixtureLayer, "unstaged");
    assert.equal(evidence.errorCount, 0);
    assert.equal(evidence.status, "probe_unavailable");
    assert.equal(evidence.native.commands, null);
    assert.equal(JSON.stringify(evidence).includes("SECRET_GIT_BODY"), false);
    dom.window.document
      .querySelector(
        '[data-thread-workbench-scope="ws-current:thr-current"] [data-ja-review-shell]',
      )
      .remove();
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.status, "missing_dom");
    assert.equal(evidence.reviewCount, 0);
    assert.equal(evidence.renderedFileCount, null);
    const background = dom.window.document.querySelector(
      '[data-thread-workbench-scope="ws-old:thr-old"]',
    );
    background.removeAttribute("hidden");
    background.removeAttribute("inert");
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.status, "owner_not_unique");
    assert.equal(evidence.ownerScope, null);
  } finally {
    dom.window.close();
  }
});

/** 警告只触发一次静态采样；事件阶段与后续 DOM 采样分开，不收集正文、路径或动态 accessible name。 */
test("records one late focus sample with the warning stage and finite safe attributes", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    dom.window.document.body.insertAdjacentHTML(
      "afterbegin",
      `
      <ul aria-label="最近对话列表"><li><button aria-current="page" data-thread-id="thr-current">SECRET_THREAD_TITLE</button></li></ul>
      <div role="menu" id="portal-current" data-state="open" aria-modal="true">SECRET_MENU_BODY</div>
      <div role="dialog" id="closed-dialog" data-state="closed">SECRET_DIALOG_BODY</div>`,
    );
    const host = dom.window.document.querySelector(
      '[data-thread-workbench-scope="ws-current:thr-current"]',
    );
    host.insertAdjacentHTML(
      "beforeend",
      `<div id="focus-drawer" aria-hidden="true" data-visible="false"><button
      id="focus-entry" class="ja-entry" role="button" aria-label="SECRET_DYNAMIC_NAME" title="C:\\SECRET_PATH">SECRET_BUTTON_BODY</button></div>`,
    );
    dom.window.document.getElementById("focus-entry").focus();
    const handlers = new Map();
    let evaluateCalls = 0;
    let releaseSample;
    let stage = "first:workbench_review";
    const diagnosticPage = {
      /** 保存真正注册的事件 handler，测试不重写警告过滤或事件顺序判定。 */
      on: (event, handler) => handlers.set(event, handler),
      /** 实际页面投影延后执行，证明样本没有冒充警告瞬间的 DOM 状态。 */
      evaluate: async (callback, input) => {
        evaluateCalls += 1;
        await new Promise((resolve) => {
          releaseSample = resolve;
        });
        return page.evaluate(callback, input);
      },
    };
    const diagnostics = attachPageDiagnostics(
      diagnosticPage,
      undefined,
      /** 事件阶段只读当前 runner 记录，不受后来属性采样的时间点影响。 */
      () => stage,
    );
    handlers.get("console")({ type: () => "info", text: () => "normal console" });
    const warning = { type: () => "warning", text: () => "Blocked aria-hidden on an element" };
    handlers.get("console")(warning);
    handlers.get("console")(warning);
    stage = "first:next_stage";
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(evaluateCalls, 1);
    assert.equal(diagnostics.ariaHiddenFocusWarnings.length, 1);
    assert.equal(diagnostics.ariaHiddenFocusWarnings[0].status, "pending");
    dom.window.document.getElementById("focus-drawer").setAttribute("aria-hidden", "false");
    releaseSample();
    await new Promise((resolve) => setImmediate(resolve));
    const record = diagnostics.ariaHiddenFocusWarnings[0];
    assert.equal(record.status, "observed");
    assert.equal(record.currentStage, "first:workbench_review");
    assert.equal(record.eventNumber, 2);
    assert.equal(record.lateSample, true);
    assert.ok(record.sampledAtMonotonicMs >= record.monotonicMs);
    assert.equal(record.snapshot.activeElement.tag, "button");
    assert.equal(record.snapshot.activeElement.id, "focus-entry");
    assert.equal(record.snapshot.activeElement.class, "ja-entry");
    assert.equal(record.snapshot.activeElement.role, "button");
    assert.equal(record.snapshot.activeElement.tabIndex, 0);
    assert.equal(record.snapshot.activeElement.isConnected, true);
    assert.equal(record.snapshot.ancestors[0].ariaHidden, "false");
    assert.equal(record.snapshot.ancestors[0].dataVisible, "false");
    assert.ok(record.snapshot.ancestors.length <= 12);
    assert.equal(record.snapshot.currentThreadId, "thr-current");
    assert.equal(record.snapshot.activeHostOwner, "ws-current:thr-current");
    assert.equal(record.snapshot.focusedHostOwner, "ws-current:thr-current");
    assert.deepEqual(JSON.parse(JSON.stringify(record.snapshot.openOverlays)), [
      { role: "menu", id: "portal-current", dataState: "open", ariaModal: "true" },
    ]);
    assert.equal(/SECRET_|aria-label|outerHTML/u.test(JSON.stringify(record)), false);
    assert.deepEqual(diagnostics.console, [
      "info: normal console",
      "warning: Blocked aria-hidden on an element",
      "warning: Blocked aria-hidden on an element",
    ]);
  } finally {
    dom.window.close();
  }
});

/** 页面不响应或采样异常都只产生 unknown，原 console 保留；诊断不能无限等待或取代产品 failure。 */
test("bounds late focus capture and preserves the original console after observation failure", async () => {
  const unavailable = await captureAriaHiddenFocusSnapshot(
    { evaluate: async () => new Promise(() => {}) },
    Date.now() + 30,
  );
  assert.equal(unavailable.status, "unavailable");
  assert.equal(unavailable.snapshot, "unknown");
  assert.equal(unavailable.lateSample, true);
  const handlers = new Map();
  const diagnostics = attachPageDiagnostics(
    {
      /** 只保存原注册回调，异常必须由正式 handler 的 best-effort 路径处理。 */
      on: (event, handler) => handlers.set(event, handler),
      /** 原始异常可能含正文，持久化诊断只能记录 unavailable。 */
      evaluate: async () => {
        throw new Error("SECRET_PAGE_ERROR");
      },
    },
    undefined,
    /** 阶段也可能不可取得，此时不能编造一个 setup 或动作名。 */
    () => {
      throw new Error("SECRET_STAGE_ERROR");
    },
  );
  handlers.get("console")({
    type: () => "warning",
    text: () => "Blocked aria-hidden on an element",
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(diagnostics.console, ["warning: Blocked aria-hidden on an element"]);
  assert.equal(diagnostics.ariaHiddenFocusWarnings[0].currentStage, "unknown");
  assert.equal(diagnostics.ariaHiddenFocusWarnings[0].status, "unavailable");
  assert.equal(diagnostics.ariaHiddenFocusWarnings[0].snapshot, "unknown");
  assert.equal(/SECRET_/u.test(JSON.stringify(diagnostics)), false);
});

/** 真实关闭结果允许主 Composer、标题栏与已有外部安全控件；隐藏 Workbench 和保留 Thread 的焦点都不能通过。 */
test("requires visible current-page focus outside every hidden Workbench after a close", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    dom.window.document.body.insertAdjacentHTML(
      "beforeend",
      `
      <section id="conversation"><textarea aria-label="消息">SECRET_COMPOSER_VALUE</textarea></section>
      <button class="ja-titlebar-button">SECRET_TITLEBAR_LABEL</button><button id="safe-external">SECRET_EXTERNAL_LABEL</button>`,
    );
    const inspector = dom.window.document.querySelector(
      '[data-thread-workbench-scope="ws-current:thr-current"] .ja-inspector',
    );
    inspector.setAttribute("data-visible", "false");
    inspector.insertAdjacentHTML(
      "beforeend",
      '<button id="stale-workbench-focus">SECRET_OLD_FOCUS</button>',
    );
    /** JSDOM 替身只提供非零矩形，hidden/inert/aria-hidden 和 owner 仍由实际页面函数检查。 */
    dom.window.HTMLElement.prototype.getClientRects = function () {
      return [{ width: 80, height: 20 }];
    };
    dom.window.document.getElementById("stale-workbench-focus").focus();
    let evidence = await page.evaluate(workbenchCloseFocusEvidenceInPage, {
      ownerScope: "ws-current:thr-current",
    });
    assert.equal(evidence.ready, false);
    assert.equal(evidence.insideWorkbench, true);
    assert.equal(evidence.visible, false);
    for (const [selector, targetKind] of [
      ['#conversation [aria-label="消息"]', "main_composer"],
      ["button.ja-titlebar-button", "titlebar_control"],
      ["#safe-external", "external_control"],
    ]) {
      dom.window.document.querySelector(selector).focus();
      evidence = await waitForWorkbenchCloseFocus(
        page,
        "ws-current:thr-current",
        Date.now() + 1_000,
      );
      assert.equal(evidence.ready, true);
      assert.equal(evidence.currentOwnerMatches, true);
      assert.equal(evidence.insideWorkbench, false);
      assert.equal(evidence.visible, true);
      assert.equal(evidence.targetKind, targetKind);
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    }
    dom.window.document.getElementById("safe-external").setAttribute("aria-hidden", "true");
    assert.equal(
      (
        await page.evaluate(workbenchCloseFocusEvidenceInPage, {
          ownerScope: "ws-current:thr-current",
        })
      ).ready,
      false,
    );
    dom.window.document.getElementById("safe-external").removeAttribute("aria-hidden");
    assert.equal(
      (await page.evaluate(workbenchCloseFocusEvidenceInPage, { ownerScope: "ws-other:thr-other" }))
        .ready,
      false,
    );
    const retained = dom.window.document.querySelector(
      '[data-thread-workbench-scope="ws-old:thr-old"]',
    );
    retained.insertAdjacentHTML(
      "beforeend",
      '<textarea id="retained-composer" aria-label="消息"></textarea>',
    );
    dom.window.document.getElementById("retained-composer").focus();
    evidence = await page.evaluate(workbenchCloseFocusEvidenceInPage, {
      ownerScope: "ws-current:thr-current",
    });
    assert.equal(evidence.ready, false);
    assert.equal(evidence.insideWorkbench, true);
    assert.equal(evidence.visible, false);
  } finally {
    dom.window.close();
  }
});

/** 关闭后的页面读取也受 Node 截止约束；观察缺失不能返回焦点已经迁出的虚构结果。 */
test("bounds a stalled Workbench close focus observation", async () => {
  await assert.rejects(
    waitForWorkbenchCloseFocus(
      { evaluate: async () => new Promise(() => {}) },
      "ws:thr",
      Date.now() + 30,
    ),
    (error) => {
      assert.match(error.message, /Workbench 关闭后焦点未收敛/u);
      assert.equal(error.message.includes('"status":"unknown"'), true);
      return true;
    },
  );
});

/** 实际执行 probe 与 trace 投影，确认 Runtime ready identity 是显式派生值且 DTO 扩展不进入摘要。 @author kongweiguang */
test("projects Runtime invoke status fields and derives ready identity without leaking DTO extras", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const secret = "SECRET_RUNTIME_PATH_AND_TOKEN";
    await invoke(
      { command: "ja_runtime_start", args: { config: { token: secret, rootPath: secret } } },
      async () => ({
        status: "starting",
        generation: 0,
        serverInstanceId: null,
        features: [],
        storageRecovery: { directory: secret },
        rootPath: secret,
      }),
    );
    await invoke({ command: "ja_runtime_state", args: {} }, async () => ({
      status: "ready",
      generation: 7,
      serverInstanceId: "srv_native_identity_7",
      features: ["task_threads", "plan"],
      storageRecovery: { directory: secret },
      providerSecret: secret,
    }));
    await invoke({ command: "ja_runtime_state", args: {} }, async () => ({
      status: "ready",
      generation: 0,
      serverInstanceId: "not-a-server-id",
      features: [],
    }));

    const evidence = JSON.parse(JSON.stringify(await captureRuntimeBindingInvokeTrace(page)));
    assert.equal(evidence.status, "observed");
    assert.deepEqual(evidence.commands.ja_runtime_start.trace, [
      { phase: "start" },
      { phase: "resolved", status: "starting", generation: 0 },
    ]);
    assert.deepEqual(evidence.commands.ja_runtime_state.trace.slice(0, 2), [
      { phase: "start" },
      {
        phase: "resolved",
        status: "ready",
        generation: 7,
        serverInstanceId: "srv_native_identity_7",
        readyIdentityValid: true,
      },
    ]);
    assert.deepEqual(evidence.commands.ja_runtime_state.trace.slice(2), [
      { phase: "start" },
      { phase: "resolved", status: "ready", generation: 0, readyIdentityValid: false },
    ]);
    assert.equal(evidence.commands.ja_runtime_workspace_activate.status, "unknown");
    assert.equal(evidence.commands.ja_runtime_workspace_activate.trace, null);
    assert.equal(
      /SECRET_|rootPath|providerSecret|storageRecovery|features/u.test(JSON.stringify(evidence)),
      false,
    );
    assert.equal(
      /SECRET_/u.test(JSON.stringify(dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__)),
      false,
    );
  } finally {
    dom.window.close();
  }
});

/** Workspace activation 只投影请求/返回 ID 与 schema kind；open 的路径参数不进入这条专用观察通道。 @author kongweiguang */
test("projects matched and mismatched Workspace activation IDs without path or open-command capture", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const secret = "SECRET_WORKSPACE_ROOT_AND_CREDENTIAL";
    await invoke(
      {
        command: "ja_runtime_workspace_activate",
        args: { input: { workspaceId: "ws_project_expected", rootPath: secret } },
      },
      async () => ({
        workspaceId: "ws_project_expected",
        kind: "project",
        displayName: secret,
        trust: "trusted",
        rootPath: secret,
        token: secret,
      }),
    );
    await invoke(
      {
        command: "ja_runtime_workspace_activate",
        args: { input: { workspaceId: "ws_session_expected" } },
      },
      async () => ({
        workspaceId: "ws_session_returned",
        kind: "session",
        displayName: secret,
        trust: "untrusted",
        rootPath: secret,
      }),
    );
    await invoke(
      {
        command: "ja_runtime_workspace_activate",
        args: { input: { workspaceId: secret } },
      },
      async () => ({ workspaceId: secret, kind: "invalid", rootPath: secret }),
    );
    await invoke(
      { command: "ja_runtime_workspace_open", args: { input: { path: secret } } },
      async () => ({ workspaceId: "ws_unobserved", rootPath: secret }),
    );

    const evidence = JSON.parse(JSON.stringify(await captureRuntimeBindingInvokeTrace(page)));
    const observations = evidence.commands.ja_runtime_workspace_activate.trace;
    assert.deepEqual(observations, [
      { phase: "start", requestedWorkspaceId: "ws_project_expected" },
      {
        phase: "resolved",
        requestedWorkspaceId: "ws_project_expected",
        workspaceId: "ws_project_expected",
        kind: "project",
      },
      { phase: "start", requestedWorkspaceId: "ws_session_expected" },
      {
        phase: "resolved",
        requestedWorkspaceId: "ws_session_expected",
        workspaceId: "ws_session_returned",
        kind: "session",
      },
      { phase: "start" },
      { phase: "resolved" },
    ]);
    assert.equal(
      dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__.ja_runtime_workspace_open,
      undefined,
    );
    assert.equal(
      /SECRET_|rootPath|displayName|trust|token|path/u.test(JSON.stringify(evidence)),
      false,
    );
    assert.equal(
      /SECRET_/u.test(JSON.stringify(dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__)),
      false,
    );
  } finally {
    dom.window.close();
  }
});

/** 只接受 Runtime Rust wire 的固定 error code；消息与任意扩展码只能归为 unknown。 @author kongweiguang */
test("keeps Runtime rejections to stable codes and rejects message-derived or sensitive codes", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const stableError = { code: "UNKNOWN_WORKSPACE", message: "SECRET_RUNTIME_FAILURE" };
    await assert.rejects(
      invoke(
        {
          command: "ja_runtime_workspace_activate",
          args: { input: { workspaceId: "ws_missing_workspace" } },
        },
        async () => {
          throw stableError;
        },
      ),
      (error) => error === stableError,
    );
    const sensitiveError = {
      code: "SECRET_UNTRUSTED_CODE",
      message: "UNKNOWN_WORKSPACE SECRET_RUNTIME_FAILURE",
    };
    await assert.rejects(
      invoke({ command: "ja_runtime_state", args: {} }, async () => {
        throw sensitiveError;
      }),
      (error) => error === sensitiveError,
    );

    const evidence = JSON.parse(JSON.stringify(await captureRuntimeBindingInvokeTrace(page)));
    assert.deepEqual(evidence.commands.ja_runtime_workspace_activate.trace, [
      { phase: "start", requestedWorkspaceId: "ws_missing_workspace" },
      {
        phase: "rejected",
        errorCode: "UNKNOWN_WORKSPACE",
        requestedWorkspaceId: "ws_missing_workspace",
      },
    ]);
    assert.deepEqual(evidence.commands.ja_runtime_state.trace, [
      { phase: "start" },
      { phase: "rejected", errorCode: "unknown" },
    ]);
    assert.equal(/SECRET_|message|stack|rootPath|token/u.test(JSON.stringify(evidence)), false);
    assert.equal(
      /SECRET_/u.test(JSON.stringify(dom.window.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__)),
      false,
    );
  } finally {
    dom.window.close();
  }
});

/** Trace-only 读取没有 invoke 时返回 unknown；helper 不能自行补发 Native 查询并伪造观察。 @author kongweiguang */
test("keeps absent Runtime observations unknown and never issues a diagnostic invoke", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    assert.deepEqual(JSON.parse(JSON.stringify(await captureRuntimeBindingInvokeTrace(page))), {
      status: "unavailable",
      commands: null,
    });
    await page.evaluate(installTauriInvokeProbeInPage);
    let nativeInvokes = 0;
    dom.window.__TAURI_INTERNALS__ = {
      invoke: async () => {
        nativeInvokes += 1;
      },
    };

    const evidence = JSON.parse(JSON.stringify(await captureRuntimeBindingInvokeTrace(page)));
    assert.equal(evidence.status, "unknown");
    for (const observation of Object.values(evidence.commands)) {
      assert.deepEqual(observation, { status: "unknown", trace: null });
    }
    assert.equal(nativeInvokes, 0);
    assert.equal(/"(start|resolved|rejected)":0/u.test(JSON.stringify(evidence)), false);
  } finally {
    dom.window.close();
  }
});

/** 选会话只缩短本地等待；成功仍要求真实已启用 textarea 持有焦点，不延长全局业务 deadline。 @author kongweiguang */
test("bounds Thread reselect and still requires the enabled real Composer to own focus", async () => {
  const { dom } = nativeReviewFixture();
  try {
    const threadId = "thr_focus_bounded";
    const textarea = dom.window.document.createElement("textarea");
    textarea.setAttribute("aria-label", "消息");
    dom.window.document.body.append(textarea);
    const list = dom.window.document.createElement("div");
    list.setAttribute("aria-label", "最近对话列表");
    const selected = dom.window.document.createElement("button");
    selected.setAttribute("aria-current", "page");
    selected.setAttribute("data-thread-id", threadId);
    list.append(selected);
    dom.window.document.body.append(list);
    textarea.focus();

    const waitTimeouts = [];
    const page = {
      locator: () => ({
        waitFor: async ({ timeout }) => waitTimeouts.push(timeout),
        click: async ({ timeout }) => waitTimeouts.push(timeout),
      }),
      waitForFunction: async (callback, argument, { timeout }) => {
        waitTimeouts.push(timeout);
        const result = dom.window.Function(
          "argument",
          `return (${callback.toString()})(argument);`,
        )(argument);
        if (result !== true) throw new Error("native focus predicate did not pass");
      },
    };
    const deadline = Date.now() + 30 * 60_000;
    await selectThreadById(page, threadId, deadline, new AbortController().signal);
    assert.equal(waitTimeouts.length, 4);
    assert.equal(
      waitTimeouts.every((timeout) => timeout > 0 && timeout <= 60_000),
      true,
    );

    textarea.disabled = true;
    await assert.rejects(
      selectThreadById(page, threadId, deadline, new AbortController().signal),
      /native focus predicate did not pass/u,
    );
    textarea.disabled = false;
    textarea.blur();
    await assert.rejects(
      selectThreadById(page, threadId, deadline, new AbortController().signal),
      /native focus predicate did not pass/u,
    );
  } finally {
    dom.window.close();
  }
});

/** Probe 透明保留真实返回/拒绝；ready 拒绝 pending、错文件、all-target、重复 mutation 与仅 DOM 就绪。 */
test("requires settled native Review reads and one exact file-action acknowledgement", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const input = {
      workspaceId: "ws-current",
      source: { kind: "uncommitted" },
      revision: "SECRET_REVISION",
    };
    const response = { content: "SECRET_GIT_BODY", other: "SECRET_RESPONSE" };
    /** 这里只验证诊断是否原样交回 delegate，夹具返回不能作为产品 Git 状态证明。 */
    const delegate = async () => response;
    assert.equal(
      await invoke({ command: "ja_review_catalog", args: { input } }, delegate),
      response,
    );
    let settle;
    const pending = invoke(
      { command: "ja_review_snapshot", args: { input } },
      () =>
        new Promise((resolve) => {
          settle = resolve;
        }),
    );
    const expected = { sourceKind: "uncommitted", rangeLabel: "未暂存", fixtureLayer: "unstaged" };
    let evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(nativeReviewEvidenceReady(evidence, expected), false);
    assert.equal(evidence.native.commands.ja_review_snapshot.counts.start, 1);
    assert.equal(evidence.native.commands.ja_review_snapshot.counts.resolved, 0);
    settle(response);
    assert.equal(await pending, response);
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(nativeReviewEvidenceReady(evidence, expected), true);
    const refresh = dom.window.document.querySelector('button[aria-label="重新读取审阅"]');
    refresh.disabled = true;
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), expected),
      false,
    );
    refresh.disabled = false;
    await invoke(
      { command: "ja_review_file_diff", args: { input: { ...input, fileId: "other-file" } } },
      delegate,
    );
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
        ...expected,
        fileId: "file-current",
      }),
      false,
    );
    await invoke(
      { command: "ja_review_file_diff", args: { input: { ...input, fileId: "file-current" } } },
      delegate,
    );
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
        ...expected,
        fileId: "file-current",
      }),
      true,
    );
    for (const target of [{ kind: "all" }, { kind: "file", fileId: "other-file" }]) {
      const minCallId =
        (await page.evaluate(nativeReviewEvidenceInPage)).native.commands.ja_review_apply.counts
          .start + 1;
      await invoke(
        { command: "ja_review_apply", args: { input: { ...input, action: "stage", target } } },
        delegate,
      );
      assert.equal(
        nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
          ...expected,
          apply: { action: "stage", fileId: "file-current", minCallId },
        }),
        false,
      );
    }
    const apply = { action: "stage", fileId: "file-current", minCallId: 3 };
    const actionCall = {
      command: "ja_review_apply",
      args: {
        input: { ...input, action: "stage", target: { kind: "file", fileId: "file-current" } },
      },
    };
    await invoke(actionCall, delegate);
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
        ...expected,
        apply,
      }),
      true,
    );
    await invoke(actionCall, delegate);
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
        ...expected,
        apply,
      }),
      false,
    );
    const rejection = { code: "REVIEW_CONFLICT", message: "SECRET_NATIVE_ERROR" };
    await assert.rejects(
      invoke(actionCall, async () => {
        throw rejection;
      }),
      (error) => error === rejection,
    );
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.status, "invoke_error");
    assert.equal(
      evidence.native.commands.ja_review_apply.trace.at(-1).errorCode,
      "REVIEW_CONFLICT",
    );
    assert.equal(nativeReviewEvidenceReady(evidence, expected), false);
    assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    await invoke(actionCall, delegate);
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.status, "observed");
    assert.equal(nativeReviewEvidenceReady(evidence, { ...expected, apply }), false);
  } finally {
    dom.window.close();
  }
});

/** 三十个旧终态能挤掉 32 条尾部中的早期拒绝/重复；整窗判定必须保留真实完整 64 条 trace。 */
test("retains early Apply rejection and duplicate acknowledgements beyond a 32-entry diagnostic tail", async () => {
  for (const earlyPhase of ["rejected", "resolved"]) {
    const { dom, page } = nativeReviewFixture();
    try {
      await page.evaluate(installTauriInvokeProbeInPage);
      const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
      const input = {
        workspaceId: "ws-current",
        source: { kind: "uncommitted" },
        revision: "SECRET_REVISION",
      };
      const actionCall = {
        command: "ja_review_apply",
        args: {
          input: { ...input, action: "stage", target: { kind: "file", fileId: "file-current" } },
        },
      };
      /** 返回内容只验证 probe 的脱敏边界，不作为真实 native mutation 成功证明。 */
      const resolved = async () => ({ content: "SECRET_NATIVE_RESPONSE" });
      await invoke(
        { command: "ja_review_catalog", args: { input: { workspaceId: input.workspaceId } } },
        resolved,
      );
      await invoke({ command: "ja_review_snapshot", args: { input } }, resolved);
      const oldSettlers = [];
      const oldCalls = [];
      for (let index = 0; index < 30; index += 1) {
        oldCalls.push(
          invoke(
            actionCall,
            /** 旧调用先 start，再在当前窗口的早期结果之后真实结算，不能手工拼 trace。 */
            () =>
              new Promise((resolve) => {
                oldSettlers.push(resolve);
              }),
          ),
        );
      }
      const minCallId = 31;
      if (earlyPhase === "rejected") {
        const rejection = { code: "REVIEW_CONFLICT", message: "SECRET_EARLY_APPLY_ERROR" };
        await assert.rejects(
          invoke(
            actionCall,
            /** 当前窗口的真实拒绝必须保留，即使后续还有一个正确文件 ACK。 */
            async () => {
              throw rejection;
            },
          ),
          (error) => error === rejection,
        );
      } else {
        await invoke(actionCall, resolved);
      }
      for (const settle of oldSettlers) settle({ content: "SECRET_OLD_APPLY_RESPONSE" });
      await Promise.all(oldCalls);
      await invoke(actionCall, resolved);
      const evidence = await page.evaluate(nativeReviewEvidenceInPage);
      const mutation = evidence.native.commands.ja_review_apply;
      assert.equal(evidence.status, "observed");
      assert.equal(mutation.traceTruncated, false);
      assert.equal(mutation.trace.length, 64);
      assert.equal(mutation.counts.start, 32);
      assert.equal(mutation.counts.resolved, earlyPhase === "rejected" ? 31 : 32);
      assert.equal(mutation.counts.rejected, earlyPhase === "rejected" ? 1 : 0);
      assert.equal(
        mutation.trace.find((entry) => entry.reviewCallId === 31 && entry.phase === earlyPhase)
          ?.phase,
        earlyPhase,
      );
      const diagnosticTail = mutation.trace.slice(-32);
      assert.equal(
        diagnosticTail.some((entry) => entry.reviewCallId === 31),
        false,
      );
      assert.equal(diagnosticTail.filter((entry) => entry.reviewCallId >= minCallId).length, 2);
      const expected = {
        sourceKind: "uncommitted",
        apply: { action: "stage", fileId: "file-current", minCallId },
      };
      assert.equal(nativeReviewEvidenceReady(evidence, expected), false);
      assert.equal(
        nativeReviewEvidenceReady(evidence, {
          ...expected,
          apply: { ...expected.apply, minCallId: 32 },
        }),
        true,
      );
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    } finally {
      dom.window.close();
    }
  }
});

/** 旧终态只能解释旧请求；当前请求的最大 start ID 未完成时必须继续等待，完成后才可放行。 */
test("waits for the latest started Review read when an older rejection arrives late", async () => {
  for (const command of ["ja_review_catalog", "ja_review_snapshot", "ja_review_file_diff"]) {
    const { dom, page } = nativeReviewFixture();
    try {
      await page.evaluate(installTauriInvokeProbeInPage);
      const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
      const input = {
        workspaceId: "ws-current",
        source: { kind: "uncommitted" },
        fileId: "file-current",
      };
      const expected = { sourceKind: "uncommitted", fileId: "file-current" };
      /** 只验证 probe 保留 delegate 返回值，成功夹具不替代实际 Rust/Git 验收。 */
      const resolved = async () => ({ content: "SECRET_NATIVE_READ_BODY" });
      await invoke(
        { command: "ja_review_catalog", args: { input: { workspaceId: input.workspaceId } } },
        resolved,
      );
      await invoke({ command: "ja_review_snapshot", args: { input } }, resolved);
      await invoke({ command: "ja_review_file_diff", args: { input } }, resolved);
      let rejectOld;
      let resolveCurrent;
      const oldInput =
        command === "ja_review_catalog"
          ? { workspaceId: input.workspaceId }
          : command === "ja_review_file_diff"
            ? { ...input, fileId: "file-old" }
            : input;
      const currentInput =
        command === "ja_review_catalog" ? { workspaceId: input.workspaceId } : input;
      const old = invoke(
        { command, args: { input: oldInput } },
        /** 可控的真实 Promise 制造旧调用晚到，不手写 ready 判定。 */
        () =>
          new Promise((_resolve, reject) => {
            rejectOld = reject;
          }),
      );
      const oldRejected = assert.rejects(
        old,
        (error) => error === "SECRET_OLD_REJECTION CANCELLED",
      );
      const current = invoke(
        { command, args: { input: currentInput } },
        /** 新请求确实保持 pending，不能凭 DOM 无 loading 认作完成。 */
        () =>
          new Promise((resolve) => {
            resolveCurrent = resolve;
          }),
      );
      rejectOld("SECRET_OLD_REJECTION CANCELLED");
      await oldRejected;
      let evidence = await page.evaluate(nativeReviewEvidenceInPage);
      const observation = evidence.native.commands[command];
      assert.equal(observation.trace.at(-1).phase, "rejected");
      assert.equal(observation.trace.at(-1).reviewCallId, 2);
      assert.equal(observation.trace.at(-1).errorCode, "unknown");
      assert.equal(observation.currentRead.reviewCallId, 3);
      assert.equal(observation.currentRead.status, "pending");
      assert.equal(evidence.status, "pending");
      assert.equal(nativeReviewEvidenceReady(evidence, expected), false);
      resolveCurrent({ content: "SECRET_NATIVE_READ_BODY" });
      await current;
      evidence = await page.evaluate(nativeReviewEvidenceInPage);
      assert.equal(evidence.native.commands[command].currentRead.status, "resolved");
      assert.equal(evidence.status, "observed");
      assert.equal(nativeReviewEvidenceReady(evidence, expected), true);
      if (command === "ja_review_snapshot") {
        assert.equal(
          nativeReviewEvidenceReady(evidence, { ...expected, minSnapshotCallId: 3 }),
          true,
        );
        assert.equal(
          nativeReviewEvidenceReady(evidence, { ...expected, minSnapshotCallId: 4 }),
          false,
        );
      }
      const unrelatedInput =
        command === "ja_review_catalog"
          ? { workspaceId: "ws-old" }
          : { ...input, source: { kind: "commit", commitId: "fixture-other-source" } };
      const unrelatedRejection = { code: "REVIEW_CONFLICT", message: "SECRET_UNRELATED_REJECTION" };
      await assert.rejects(
        invoke(
          { command, args: { input: unrelatedInput } },
          /** 同 Workspace 的其它来源和其它 Workspace 都不能替代当前读请求身份。 */
          async () => {
            throw unrelatedRejection;
          },
        ),
        (error) => error === unrelatedRejection,
      );
      evidence = await page.evaluate(nativeReviewEvidenceInPage);
      assert.equal(evidence.native.commands[command].currentRead.reviewCallId, 3);
      assert.equal(evidence.status, "observed");
      assert.equal(nativeReviewEvidenceReady(evidence, expected), true);
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    } finally {
      dom.window.close();
    }
  }
});

/** 当前请求失败必须由同 ID 保留，旧 resolved 晚到也不能把它改成成功。 */
test("keeps the latest Review read rejection after an older resolution arrives", async () => {
  for (const command of ["ja_review_catalog", "ja_review_snapshot", "ja_review_file_diff"]) {
    const { dom, page } = nativeReviewFixture();
    try {
      await page.evaluate(installTauriInvokeProbeInPage);
      const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
      const input = {
        workspaceId: "ws-current",
        source: { kind: "uncommitted" },
        fileId: "file-current",
      };
      /** 原生调用结算顺序由 Promise 控制，错误对象仍由真实 probe 透传。 */
      const resolved = async () => undefined;
      await invoke(
        { command: "ja_review_catalog", args: { input: { workspaceId: input.workspaceId } } },
        resolved,
      );
      await invoke({ command: "ja_review_snapshot", args: { input } }, resolved);
      await invoke({ command: "ja_review_file_diff", args: { input } }, resolved);
      let resolveOld;
      let rejectCurrent;
      const readInput =
        command === "ja_review_catalog" ? { workspaceId: input.workspaceId } : input;
      const old = invoke(
        { command, args: { input: readInput } },
        /** 旧请求先 start 并保持未结算，以验证它最后到达时不能遮住当前失败。 */
        () =>
          new Promise((resolve) => {
            resolveOld = resolve;
          }),
      );
      const current = invoke(
        { command, args: { input: readInput } },
        /** 最大 call ID 的失败才决定当前读取，不能按最后到达事件决定。 */
        () =>
          new Promise((_resolve, reject) => {
            rejectCurrent = reject;
          }),
      );
      const rejection = { code: "REVIEW_CONFLICT", message: "SECRET_CURRENT_REJECTION" };
      const currentRejected = assert.rejects(current, (error) => error === rejection);
      rejectCurrent(rejection);
      await currentRejected;
      resolveOld(undefined);
      await old;
      const evidence = await page.evaluate(nativeReviewEvidenceInPage);
      const observation = evidence.native.commands[command];
      assert.equal(observation.trace.at(-1).phase, "resolved");
      assert.equal(observation.trace.at(-1).reviewCallId, 2);
      assert.equal(observation.currentRead.reviewCallId, 3);
      assert.equal(observation.currentRead.status, "rejected");
      assert.equal(observation.currentRead.errorCode, "REVIEW_CONFLICT");
      assert.equal(evidence.status, "invoke_error");
      assert.equal(
        nativeReviewEvidenceReady(evidence, { sourceKind: "uncommitted", fileId: "file-current" }),
        false,
      );
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    } finally {
      dom.window.close();
    }
  }
});

/** 未调用和真实 probe 容量截断都必须保持 unknown；不能从仍可见的旧 resolved 或 DOM 推断完成。 */
test("keeps Review reads unknown without a start or after the real probe trace truncates", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const input = { workspaceId: "ws-current", source: { kind: "uncommitted" } };
    /** 通过真实调用耗尽 probe 固定容量，避免手工构造 traceTruncated 条件。 */
    const resolved = async () => undefined;
    await invoke(
      { command: "ja_review_catalog", args: { input: { workspaceId: input.workspaceId } } },
      resolved,
    );
    let evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.native.commands.ja_review_snapshot.currentRead.status, "unknown");
    assert.equal(evidence.native.commands.ja_review_snapshot.currentRead.reason, "missing_start");
    assert.equal(nativeReviewEvidenceReady(evidence, { sourceKind: "uncommitted" }), false);
    await invoke({ command: "ja_review_snapshot", args: { input } }, resolved);
    assert.equal(
      nativeReviewEvidenceReady(await page.evaluate(nativeReviewEvidenceInPage), {
        sourceKind: "uncommitted",
      }),
      true,
    );
    for (let index = 0; index < 256; index += 1) {
      await invoke(
        { command: "ja_review_catalog", args: { input: { workspaceId: input.workspaceId } } },
        resolved,
      );
    }
    evidence = await page.evaluate(nativeReviewEvidenceInPage);
    assert.equal(evidence.native.commands.ja_review_catalog.traceTruncated, true);
    assert.equal(evidence.native.commands.ja_review_catalog.currentRead.status, "unknown");
    assert.equal(evidence.native.commands.ja_review_catalog.currentRead.reason, "trace_truncated");
    assert.equal(nativeReviewEvidenceReady(evidence, { sourceKind: "uncommitted" }), false);
  } finally {
    dom.window.close();
  }
});

/** 只保存当前 Rust DTO 的显式稳定码；旧码、消息中的码和缺码都不允许推断成取消。 */
test("records only explicit codes from the current native Review error envelope", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    for (const [rejection, expectedCode] of [
      [{ code: "REVIEW_STALE", message: "SECRET_NATIVE_ERROR" }, "REVIEW_STALE"],
      [{ code: "REVIEW_LIMIT", message: "SECRET_NATIVE_ERROR" }, "REVIEW_LIMIT"],
      [{ code: "CANCELLED", message: "SECRET_NATIVE_ERROR" }, "CANCELLED"],
      [{ code: "IO_ERROR", message: "SECRET_NATIVE_ERROR" }, "IO_ERROR"],
      [JSON.stringify({ code: "CANCELLED", message: "SECRET_NATIVE_ERROR" }), "unknown"],
      ["IO_ERROR", "unknown"],
      [{ code: "REVIEW_CANCELLED", message: "SECRET_NATIVE_ERROR" }, "unknown"],
      [{ message: "SECRET_NATIVE_ERROR CANCELLED" }, "unknown"],
      ["SECRET_NATIVE_ERROR CANCELLED temporarily unavailable", "unknown"],
    ]) {
      await assert.rejects(
        invoke(
          {
            command: "ja_review_snapshot",
            args: { input: { workspaceId: "ws-current", source: { kind: "uncommitted" } } },
          },
          /** 原始 rejection 仍交回调用方，诊断不能落盘其 message 或 body。 */
          async () => {
            throw rejection;
          },
        ),
        (error) => error === rejection,
      );
      const evidence = await page.evaluate(nativeReviewEvidenceInPage);
      assert.equal(evidence.native.commands.ja_review_snapshot.currentRead.errorCode, expectedCode);
      assert.equal(evidence.status, "invoke_error");
      assert.equal(/SECRET_/u.test(JSON.stringify(evidence)), false);
    }
  } finally {
    dom.window.close();
  }
});

/** 卡住的页面诊断必须在局部 deadline 返回 unknown，不能把未取得的 native 数据补成空数组或零计数。 */
test("bounds Review diagnostics without inventing native counts after a stalled page read", async () => {
  const page = { evaluate: async () => new Promise(() => {}) };
  const evidence = await captureNativeReviewEvidence(page, Date.now() + 30);
  assert.equal(evidence.status, "unavailable");
  assert.equal(evidence.native.status, "unavailable");
  assert.equal(evidence.native.commands, null);
});

const pureDeliveryEvidence = Object.freeze({
  targetActivity: "idle",
  providerTurnDelta: 0,
  turnStartDelta: 0,
  followupDelta: 0,
  turnStateUnchanged: true,
  sameReceipt: true,
  mailbox: Object.freeze({
    rowCount: 1,
    state: "PENDING",
    boundTurnPresent: false,
    boundTurnMatchesActiveTurn: false,
    consumedAtPresent: false,
  }),
});

const runningDeliveryEvidence = Object.freeze({
  ...pureDeliveryEvidence,
  targetActivity: "running",
  mailbox: Object.freeze({
    rowCount: 1,
    state: "BOUND",
    boundTurnPresent: true,
    boundTurnMatchesActiveTurn: true,
    consumedAtPresent: false,
  }),
});

/** 构造脱敏的逐 Thread projection fixture；只含身份、布尔标记和计数，不伪造正文。 @author kongweiguang */
function timelineProjection(threadId, overrides = {}) {
  return {
    threadId,
    threadPresent: false,
    threadRevisionPresent: false,
    snapshotRevisionPresent: false,
    resyncRequiredPresent: false,
    recoveredActiveTurnPresent: false,
    resyncRequestSequencePresent: false,
    lastAcceptedLivePresent: false,
    turnCount: 0,
    completedTurnCount: 0,
    runningTurnCount: 0,
    turnIndexCount: 0,
    itemCount: 0,
    itemIndexCount: 0,
    itemOwnerIndexCount: 0,
    itemBytesIndexCount: 0,
    itemContentCount: 0,
    answerContentCount: 0,
    toolCorrelationCount: 0,
    pendingToolOrdinalCount: 0,
    liveStartedToolCount: 0,
    approvalCount: 0,
    approvalIndexCount: 0,
    contextCompactionCount: 0,
    contextUsageInvalidationCount: 0,
    contextUsageCount: 0,
    taskActivityCount: 0,
    inputQueueCount: 0,
    streamSequenceCount: 0,
    draftCount: 0,
    retryAttemptCount: 0,
    retryingCount: 0,
    ...overrides,
  };
}

const closedTimelineEvidence = Object.freeze({
  childThreadId: "thr_closed_side",
  closedThreadIds: Object.freeze(["thr_closed_side"]),
  beforeClose: Object.freeze({
    status: "captured",
    projections: Object.freeze([
      timelineProjection("thr_closed_side", {
        threadPresent: true,
        threadRevisionPresent: true,
        turnCount: 1,
        completedTurnCount: 1,
        turnIndexCount: 1,
        itemCount: 1,
        itemIndexCount: 1,
        itemOwnerIndexCount: 1,
        itemBytesIndexCount: 1,
        itemContentCount: 1,
        answerContentCount: 1,
      }),
    ]),
  }),
  afterClose: Object.freeze({
    status: "captured",
    projections: Object.freeze([timelineProjection("thr_closed_side")]),
  }),
  final: Object.freeze({
    status: "captured",
    projections: Object.freeze([timelineProjection("thr_closed_side")]),
  }),
});

const rootThreadTurnReducerRecord = Object.freeze({
  turn_root_source_context: Object.freeze({ threadId: "thr_task_root", status: "completed" }),
  turn_root_continuation: Object.freeze({ threadId: "thr_task_root", status: "completed" }),
  turn_side_followup: Object.freeze({ threadId: "thr_task_side", status: "completed" }),
});
const acceptedRootTurnIds = Object.freeze(["turn_root_source_context", "turn_root_continuation"]);

const validTaskThreadsAcceptanceEvidence = Object.freeze({
  status: "passed",
  followupDelta: 1,
  explicitSideChatFollowupDelta: 1,
  pureDelivery: Object.freeze([
    pureDeliveryEvidence,
    runningDeliveryEvidence,
    pureDeliveryEvidence,
  ]),
  closedTimeline: closedTimelineEvidence,
  conversationRootNativeStartCount: 2,
  conversationRootNativeAcceptedCount: 2,
  conversationRootAcceptedTurnIdsMatchReducer: taskTurnAcceptedIdsMatchReducer(
    acceptedRootTurnIds,
    rootThreadTurnReducerRecord,
    "thr_task_root",
  ),
  conversationRootTurnCount: 2,
  conversationRootCompletedTurnCount: 2,
  conversationRootRunningCount: 0,
  conversationChildTurnCount: 0,
  conversationChildCompletedTurnCount: 0,
  conversationChildRunningCount: 0,
});

/** 两次显式 root native start 必须与两个 root reducer terminal ID 对账；唯一 follow-up 属于侧聊。 @author kongweiguang */
test("accepts two root turns and one explicit side-chat turn with exact native/reducer identity", () => {
  assert.deepEqual(assertTaskThreadsAcceptanceEvidence(validTaskThreadsAcceptanceEvidence), {
    status: "passed",
  });
});

/** Native exit 位于首阶段 summary；不得从 taskThreads 子摘要回退或接受缺失事实。 @author kongweiguang */
test("reads native exit evidence from the captured first-phase report path only", async () => {
  const nativeExit = Object.freeze({
    status: "passed",
    nativeScopedCloseReadiness: Object.freeze({ status: "derived_ready" }),
    closePreferenceSaveResolved: true,
    commitStartedCount: 1,
    commitResolvedCount: 0,
    cancelStartedCount: 0,
    nativeProcessTree: Object.freeze({
      snapshotStatus: "fresh",
      liveCount: 0,
      incompleteLiveCount: 0,
    }),
    exitTrace: Object.freeze({ status: "complete" }),
    exitCleanupFailureMarkerObserved: false,
  });
  const capturedSummary = Object.freeze({
    profilePrime: Object.freeze({ ownedTreeCleanup: Object.freeze({ status: "passed" }) }),
    first: Object.freeze({
      taskThreads: validTaskThreadsAcceptanceEvidence,
      nativeExit,
      ownedTreeCleanup: Object.freeze({ status: "not_exercised" }),
    }),
    second: Object.freeze({
      taskThreads: Object.freeze({ status: "passed" }),
      ownedTreeCleanup: Object.freeze({ status: "passed" }),
    }),
    taskThreadsSharedRuntime: Object.freeze({ status: "passed", sameBackend: true }),
  });
  assert.equal(taskNativeExitEvidenceIsComplete(capturedSummary.first), true);
  assert.equal(taskNativeExitEvidenceIsComplete({ taskThreads: { nativeExit } }), false);
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /evidence\.first\.nativeExit = await requestTaskThreadsNormalExit/u);
  const gateStart = source.lastIndexOf("const taskThreads = evidence.first.taskThreads;");
  const gateEnd = source.indexOf("evidence.acceptance = { blocked: [], gated: [] };", gateStart);
  assert.ok(gateStart >= 0 && gateEnd > gateStart);
  const gate = source.slice(gateStart, gateEnd);
  assert.match(gate, /taskNativeExitEvidenceIsComplete\(evidence\.first\)/u);
  assert.doesNotMatch(gate, /taskThreads\.nativeExit/u);
});

/** reducer turns 的真实结构是 turnId->\{threadId,status\}；Turn ID 来自 key，不能伪造 value 字段。 @author kongweiguang */
test("matches exact native ACK identities against reducer turn-map keys", () => {
  assert.equal(
    taskTurnAcceptedIdsMatchReducer(
      acceptedRootTurnIds,
      rootThreadTurnReducerRecord,
      "thr_task_root",
    ),
    true,
  );
  for (const [accepted, turns, threadId] of [
    [["turn_unknown_a", "turn_unknown_b"], rootThreadTurnReducerRecord, "thr_task_root"],
    [["turn_root_source_context"], rootThreadTurnReducerRecord, "thr_task_root"],
    [
      ["turn_root_source_context", "turn_root_source_context"],
      rootThreadTurnReducerRecord,
      "thr_task_root",
    ],
    [
      acceptedRootTurnIds,
      {
        ...rootThreadTurnReducerRecord,
        turn_root_continuation: { threadId: "thr_other", status: "completed" },
      },
      "thr_task_root",
    ],
    [
      acceptedRootTurnIds,
      {
        ...rootThreadTurnReducerRecord,
        turn_root_continuation: { threadId: "thr_task_root", status: "running" },
      },
      "thr_task_root",
    ],
    [acceptedRootTurnIds, [], "thr_task_root"],
  ]) {
    assert.equal(taskTurnAcceptedIdsMatchReducer(accepted, turns, threadId), false);
  }
});

/** Close 前真实 child 必须已完成；ACK 后即时与最终均逐 ID 清空，缺读数/串 Thread/迟到事实都拒绝。 @author kongweiguang */
test("requires exact closed-thread projection purge before and after acknowledgement", () => {
  assert.deepEqual(assertTaskThreadsClosedTimelineEvidence(closedTimelineEvidence), {
    status: "passed",
  });
  const invalid = [
    {
      ...closedTimelineEvidence,
      afterClose: { status: "unavailable", projections: [] },
    },
    {
      ...closedTimelineEvidence,
      afterClose: {
        ...closedTimelineEvidence.afterClose,
        projections: [timelineProjection("thr_wrong_thread")],
      },
    },
    {
      ...closedTimelineEvidence,
      afterClose: {
        ...closedTimelineEvidence.afterClose,
        projections: [timelineProjection("thr_closed_side", { itemContentCount: 1 })],
      },
    },
    {
      ...closedTimelineEvidence,
      final: {
        ...closedTimelineEvidence.final,
        projections: [
          timelineProjection("thr_closed_side", {
            threadPresent: true,
            turnCount: 1,
            completedTurnCount: 1,
            turnIndexCount: 1,
          }),
        ],
      },
    },
    {
      ...closedTimelineEvidence,
      beforeClose: {
        ...closedTimelineEvidence.beforeClose,
        projections: [
          timelineProjection("thr_closed_side", {
            threadPresent: true,
            turnCount: 1,
            completedTurnCount: 0,
            runningTurnCount: 1,
          }),
        ],
      },
    },
  ];
  for (const evidence of invalid)
    assert.throws(() => assertTaskThreadsClosedTimelineEvidence(evidence));
});

/** 任一 root start/ACK/reducer 缺失或多出、follow-up 副作用和跨 Thread 归属均拒绝。 @author kongweiguang */
test("rejects accidental Task follow-ups and mixed root/side conversation turns", () => {
  for (const invalid of [
    [{ followupDelta: 0 }, /session_followup_count_not_exactly_one/u],
    [{ followupDelta: 2 }, /session_followup_count_not_exactly_one/u],
    [{ explicitSideChatFollowupDelta: 0 }, /explicit_side_chat_followup_not_exactly_one/u],
    [{ conversationRootNativeStartCount: 1 }, /root_thread_turn_scope_or_terminal_state_mismatch/u],
    [
      { conversationRootNativeAcceptedCount: 1 },
      /root_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      { conversationRootAcceptedTurnIdsMatchReducer: false },
      /root_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      {
        pureDelivery: [
          { ...pureDeliveryEvidence, followupDelta: 1 },
          runningDeliveryEvidence,
          pureDeliveryEvidence,
        ],
      },
      /pure_delivery_1_changed_execution_or_receipt/u,
    ],
    [
      {
        conversationRootNativeStartCount: 3,
        conversationRootNativeAcceptedCount: 3,
        conversationRootTurnCount: 3,
        conversationRootCompletedTurnCount: 3,
        conversationChildTurnCount: 0,
        conversationChildCompletedTurnCount: 0,
      },
      /root_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      { conversationChildTurnCount: 1, conversationChildCompletedTurnCount: 1 },
      /side_chat_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      { conversationChildCompletedTurnCount: 1 },
      /side_chat_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      { conversationChildTurnCount: 1, conversationChildRunningCount: 1 },
      /side_chat_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
    [
      { conversationChildRunningCount: 1 },
      /side_chat_thread_turn_scope_or_terminal_state_mismatch/u,
    ],
  ]) {
    assert.throws(
      () =>
        assertTaskThreadsAcceptanceEvidence({
          ...validTaskThreadsAcceptanceEvidence,
          ...invalid[0],
        }),
      invalid[1],
    );
  }
});

/** 真窗证据按 Thread ID 归类 native turn_start 和 reducer Turn，且将 ACK turn IDs 与终态精确匹配。 @author kongweiguang */
test("measures Task follow-up around the side Composer and scopes reducer turns by Thread ID", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runTaskThreadsAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const session = source.slice(start, end);
  assert.match(
    session,
    /const explicitSideChatFollowupBefore = await tauriInvokeCount\(page, commands\.followup\);[\s\S]*?await taskInput\.fill\(scenario\.prompt\);[\s\S]*?explicitSideChatFollowupDelta !== 1/u,
  );
  assert.match(
    session,
    /const rootTurnTraceOffset = \(await tauriInvokeTrace\(page, "ja_turn_start"\)\)\.length/u,
  );
  assert.match(session, /call\.phase === "start" && call\.threadId === rootThreadId/u);
  assert.match(
    session,
    /conversationRootAcceptedTurnIdsMatchReducer: rootAcceptedTurnIdsMatchReducer/u,
  );
  assert.match(session, /const conversationTurns = Object\.entries\(reducerTurns\)/u);
  assert.match(
    session,
    /taskTurnAcceptedIdsMatchReducer\(\s*rootTurnAcceptedIds,\s*reducerTurns,\s*rootThreadId,\s*\)/u,
  );
  const reducerIdentityStart = source.indexOf("export function taskTurnAcceptedIdsMatchReducer(");
  const reducerIdentityEnd = source.indexOf("\n}\n", reducerIdentityStart);
  const reducerIdentity = source.slice(reducerIdentityStart, reducerIdentityEnd);
  assert.match(reducerIdentity, /Object\.entries\(reducerTurns\)/u);
  assert.match(reducerIdentity, /\.map\(\(\[turnId\]\) => turnId\)/u);
  assert.doesNotMatch(reducerIdentity, /turn\.turnId/u);
  assert.match(session, /turn\?\.threadId === rootThreadId/u);
  assert.match(session, /turn\?\.threadId === childThreadId/u);
  assert.match(session, /captureTimelineThreadProjection\(page, expectedClosedThreadIds\)/u);
  assert.match(
    session,
    /timelineThreadProjectionStageIsEmpty\(timelineAfterClose, acknowledgedThreadIds\)/u,
  );
  assert.match(session, /closedTimeline,/u);
  assert.match(source, /assertTaskThreadsAcceptanceEvidence\(taskThreads\)/u);
});

/** turn_start trace 只保留符合协议的 opaque Thread ID 与接受后的 Turn ID，不保存输入正文。 @author kongweiguang */
test("records turn-start Thread ownership and ACK identity without content", async () => {
  const { dom, page } = nativeReviewFixture();
  try {
    await page.evaluate(installTauriInvokeProbeInPage);
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    await invoke(
      {
        command: "ja_turn_start",
        args: {
          input: {
            threadId: "thr_root_identity",
            content: [{ type: "text", text: "SECRET_TURN_BODY" }],
          },
        },
      },
      async () => ({ turnId: "turn_root_identity", threadRevision: 1 }),
    );
    await invoke(
      {
        command: "ja_turn_start",
        args: {
          input: {
            threadId: "legacy-thread-id",
            content: [{ type: "text", text: "SECRET_INVALID_OWNER_BODY" }],
          },
        },
      },
      async () => ({ turnId: "turn_unscoped_identity", threadRevision: 2 }),
    );
    const trace = await page.evaluate(
      () => globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__.ja_turn_start,
    );
    assert.equal(trace.filter((entry) => entry.phase === "start")[0].threadId, "thr_root_identity");
    assert.equal(
      trace.filter((entry) => entry.phase === "resolved")[0].threadId,
      "thr_root_identity",
    );
    assert.equal(trace.filter((entry) => entry.phase === "start")[1].threadId, undefined);
    assert.equal(trace.filter((entry) => entry.phase === "resolved")[1].threadId, undefined);
    assert.equal(/SECRET_/u.test(JSON.stringify(trace)), false);
  } finally {
    dom.window.close();
  }
});

/** 空闲目标只接受未 claim 的 PENDING；运行目标使用单独证据样本锁定精确 BOUND 语义。 @author kongweiguang */
test("accepts idempotent pure delivery to an idle target as PENDING", () => {
  assert.doesNotThrow(() => assertTaskThreadsDeliveryEvidence(pureDeliveryEvidence));
});

/** 运行中投递必须绑定发送前那一个活动 Turn，不能错误要求它仍处于 PENDING。 @author kongweiguang */
test("accepts running-target delivery bound to its pre-send active Turn", () => {
  assert.doesNotThrow(() => assertTaskThreadsDeliveryEvidence(runningDeliveryEvidence));
});

/** 任一执行入口增量都表明纯投递语义已破坏，且该约束对 idle/running 两条路径相同。 @author kongweiguang */
test("rejects provider requests, turn starts, follow-ups and target-state changes after delivery", () => {
  for (const evidence of [pureDeliveryEvidence, runningDeliveryEvidence]) {
    for (const invalid of [
      { providerTurnDelta: 1 },
      { turnStartDelta: 1 },
      { followupDelta: 1 },
      { turnStateUnchanged: false },
    ]) {
      assert.throws(() => assertTaskThreadsDeliveryEvidence({ ...evidence, ...invalid }));
    }
  }
});

/** 重复调用必须复用一条 durable 消息；相同 UI 文案不能代替回执和 SQLite 唯一性。 @author kongweiguang */
test("rejects a changed idempotency receipt or duplicate mailbox rows", () => {
  for (const evidence of [pureDeliveryEvidence, runningDeliveryEvidence]) {
    assert.throws(() => assertTaskThreadsDeliveryEvidence({ ...evidence, sameReceipt: false }));
    assert.throws(() =>
      assertTaskThreadsDeliveryEvidence({
        ...evidence,
        mailbox: { ...evidence.mailbox, rowCount: 2 },
      }),
    );
  }
});

/** 运行中必须写回精确活动 Turn 的 BOUND；过期 PENDING 或绑定到其他 Turn 都应失败。 @author kongweiguang */
test("rejects running delivery without the exact pre-send active Turn binding", () => {
  for (const invalidMailbox of [
    { state: "PENDING", boundTurnPresent: false, boundTurnMatchesActiveTurn: false },
    { state: "BOUND", boundTurnPresent: true, boundTurnMatchesActiveTurn: false },
    { state: "CANCELLED", boundTurnPresent: false, boundTurnMatchesActiveTurn: false },
  ]) {
    assert.throws(() =>
      assertTaskThreadsDeliveryEvidence({
        ...runningDeliveryEvidence,
        mailbox: { ...runningDeliveryEvidence.mailbox, ...invalidMailbox },
      }),
    );
  }
});

/** idle 投递不能在任何 Turn 上预先 claim，运行中的已绑定消息也不能提前标记为已消费。 @author kongweiguang */
test("rejects a bound idle message and an already-consumed running message", () => {
  assert.throws(() =>
    assertTaskThreadsDeliveryEvidence({
      ...pureDeliveryEvidence,
      mailbox: {
        ...pureDeliveryEvidence.mailbox,
        state: "BOUND",
        boundTurnPresent: true,
      },
    }),
  );
  assert.throws(() =>
    assertTaskThreadsDeliveryEvidence({
      ...runningDeliveryEvidence,
      mailbox: {
        ...runningDeliveryEvidence.mailbox,
        state: "CONSUMED",
        consumedAtPresent: true,
      },
    }),
  );
});

/** 不能把未识别的目标阶段降级成宽松投递语义。 @author kongweiguang */
test("rejects an unrecognized delivery target activity", () => {
  assert.throws(() =>
    assertTaskThreadsDeliveryEvidence({ ...pureDeliveryEvidence, targetActivity: "unknown" }),
  );
});

/** 随机根与 launcher 身份由同一 fixture 冻结，测试只改变待验证事实而不依赖本机进程。 */
function runtimeIsolationFixture(root = "C:\\temp\\ja-desktop-e2e-unit-1") {
  const settings = win32.join(root, "settings");
  const home = win32.join(settings, ".ja");
  const directories = {
    root,
    settings,
    home,
    data: win32.join(home, "data"),
    runtime: win32.join(root, "runtime"),
  };
  const launcher = {
    pid: 11,
    parentPid: 1,
    name: "cmd.exe",
    commandLine: "cmd.exe /c e2e-launcher",
    creationDate: "2026-09-29T01:00:00.000Z",
  };
  return { directories, launcher, nativeScope: { rootIdentity: launcher } };
}

/** 四种职责路径均由产品隔离树生成，保留覆盖入口以模拟错位、重复参数与共享后台进程。 */
function runtimeSidecarProcess(fixture, overrides = {}, directoryOverrides = {}) {
  const directories = fixture.directories;
  const paths = {
    home: directories.home,
    data: directories.data,
    run: directories.runtime,
    log: win32.join(directories.home, "logs", "java"),
    ...directoryOverrides,
  };
  const argumentsText = Object.entries(paths)
    .map(
      ([name, value]) => `--${name}-dir-base64=${Buffer.from(value, "utf8").toString("base64url")}`,
    )
    .join(" ");
  return {
    pid: 22,
    parentPid: fixture.launcher.pid,
    name: "java.exe",
    commandLine: `java.exe -jar isolated.jar ${argumentsText}`,
    creationDate: "2026-09-29T01:00:01.000Z",
    ...overrides,
  };
}

/** 私有 endpoint 元数据与监听 owner 组成共享归属证明；生产采样才负责读取文件和 OS listener。 */
function runtimeEndpointProof(fixture, ownerIdentity) {
  return {
    endpointPath: win32.join(fixture.directories.runtime, "app-server.endpoint.json"),
    profileDirectory: fixture.directories.settings,
    runDirectory: fixture.directories.runtime,
    endpoint: {
      host: "127.0.0.1",
      port: 49152,
      serverInstanceId: "srv_fixture",
      runtimeGeneration: 1,
    },
    ownerIdentity: { ...ownerIdentity },
  };
}

/** 无关同名进程即使先出现且恰有相同目录参数，也不能替代本 launcher 的后代。 */
test("selects the exact owned sidecar regardless of global snapshot order", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture);
  const unrelated = runtimeSidecarProcess(fixture, {
    pid: 7,
    parentPid: 2,
    commandLine: `${owned.commandLine} --token=UNRELATED_PRIVATE_TOKEN`,
  });
  const result = assertRuntimeIsolation(
    [unrelated, fixture.launcher, owned],
    fixture.directories,
    fixture.nativeScope,
  );
  assert.equal(result.appServerPid, owned.pid);
  assert.equal(result.launcherPid, fixture.launcher.pid);
  assert.equal(result.ownership, "launcher_descendant");
  assert.equal(result.appServerIdentity, owned);
  assert.equal(JSON.stringify(result).includes("commandLine"), false);
  assert.equal(JSON.stringify(result).includes("UNRELATED_PRIVATE_TOKEN"), false);
});

/** 四种职责必须精确相等，兄弟目录或含有目标路径的不同目录不能靠前缀匹配通过。 */
test("rejects a sibling-prefix directory and a swapped directory role", () => {
  for (const overrides of [
    { data: "C:\\temp\\ja-desktop-e2e-unit-1-sibling\\settings\\.ja\\data" },
    { run: "C:\\temp\\ja-desktop-e2e-unit-1\\settings\\.ja\\data" },
    { log: "C:\\temp\\ja-desktop-e2e-unit-1\\settings\\.ja\\logs\\java-backup" },
  ]) {
    const fixture = runtimeIsolationFixture();
    const owned = runtimeSidecarProcess(fixture, {}, overrides);
    assert.throws(
      () =>
        assertRuntimeIsolation([fixture.launcher, owned], fixture.directories, fixture.nativeScope),
      /owner_not_found/u,
    );
  }
});

/** 隔离根及产品目录映射自身也是信任边界，调用方不能通过更改 expected 值放宽职责。 */
test("rejects an unscoped root or changed authoritative directory mapping", () => {
  for (const patch of [
    { root: "C:\\temp\\regular-user-profile" },
    { settings: "C:\\temp\\ja-desktop-e2e-unit-1\\different-profile" },
    { home: "C:\\temp\\ja-desktop-e2e-unit-1\\home" },
    { runtime: "C:\\temp\\ja-desktop-e2e-unit-1-sibling\\runtime" },
  ]) {
    const fixture = runtimeIsolationFixture();
    assert.throws(
      () =>
        assertRuntimeIsolation(
          [fixture.launcher, runtimeSidecarProcess(fixture)],
          { ...fixture.directories, ...patch },
          fixture.nativeScope,
        ),
      /E2E/u,
    );
  }
});

/** 并发匹配只能报告歧义，选择快照第一行会把故障注入变成对错误进程的破坏。 */
test("rejects multiple owned sidecars with the same directory roles", () => {
  const fixture = runtimeIsolationFixture();
  assert.throws(
    () =>
      assertRuntimeIsolation(
        [
          fixture.launcher,
          runtimeSidecarProcess(fixture),
          runtimeSidecarProcess(fixture, { pid: 23 }),
        ],
        fixture.directories,
        fixture.nativeScope,
      ),
    /multiple_owned_sidecars/u,
  );
});

/** launcher 缺失、字段不完整或复用 PID 都使本轮归属失效，不能只靠 parentPid 继续选取。 */
test("rejects missing, incomplete and reused launcher identities", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture);
  for (const launcherRows of [
    [],
    [{ ...fixture.launcher, commandLine: "" }],
    [{ ...fixture.launcher, creationDate: "2026-09-29T01:00:02.000Z" }],
  ]) {
    assert.throws(
      () =>
        assertRuntimeIsolation([...launcherRows, owned], fixture.directories, fixture.nativeScope),
      /launcher_identity_changed/u,
    );
  }
});

/** 全局精确目录匹配还不足以证明归属，失败诊断也不能复制无关候选的命令行或目录参数。 */
test("rejects an unowned candidate without exposing its unrelated command line", () => {
  const fixture = runtimeIsolationFixture();
  const unrelated = runtimeSidecarProcess(fixture, { parentPid: 2 });
  unrelated.commandLine += " --token=UNRELATED_PRIVATE_TOKEN";
  assert.throws(
    () =>
      assertRuntimeIsolation(
        [unrelated, fixture.launcher],
        fixture.directories,
        fixture.nativeScope,
      ),
    (error) => {
      assert.match(error.message, /owner_not_found/u);
      assert.match(error.message, /"launcherPid":11/u);
      assert.match(error.message, /"candidates":\[\]/u);
      assert.equal(error.message.includes("UNRELATED_PRIVATE_TOKEN"), false);
      return true;
    },
  );
});

/** 新 launcher 可以复用旧共享服务，但必须用私有 endpoint 与完整 owner 身份补充归属。 */
test("accepts a saved shared endpoint owner with exact profile and run binding", () => {
  const fixture = runtimeIsolationFixture();
  const shared = runtimeSidecarProcess(fixture, {
    parentPid: 2,
    creationDate: "2026-09-29T00:59:00.000Z",
  });
  const result = assertRuntimeIsolation(
    [fixture.launcher, shared],
    fixture.directories,
    fixture.nativeScope,
    ["java.exe"],
    runtimeEndpointProof(fixture, shared),
  );
  assert.equal(result.appServerPid, shared.pid);
  assert.equal(result.ownership, "shared_endpoint");
});

/** @author kongweiguang launcher 自然退出后依赖先前冻结的完整 backend pair，且复验任何身份/路径漂移都拒绝。 */
test("revalidates a frozen shared Runtime after launcher exit and rejects owner or binding drift", async () => {
  const fixture = runtimeIsolationFixture();
  const owner = runtimeSidecarProcess(fixture, { parentPid: fixture.launcher.pid });
  const proof = runtimeEndpointProof(fixture, owner);
  const freeze = {
    epoch: 1,
    endpoint: proof.endpoint,
    runtimeIdentity: {
      status: "ready",
      serverInstanceId: proof.endpoint.serverInstanceId,
      generation: proof.endpoint.runtimeGeneration,
    },
    ownerIdentity: owner,
    identities: new Map([[owner.pid, owner]]),
  };
  const listeners = [
    { ownerPid: owner.pid, address: proof.endpoint.host, port: proof.endpoint.port },
  ];
  const revalidate = (overrides = {}) =>
    assertFrozenRuntimeBinding({
      freeze,
      directories: fixture.directories,
      expectedExecutableNames: ["java.exe"],
      snapshot: [owner], // Tauri launcher 已自然退出；这里只以 App Server 自身完整身份归属。
      endpoint: proof.endpoint,
      listeners,
      ...overrides,
    });

  const refreshed = revalidate();
  assert.equal(refreshed.ownerIdentity.pid, owner.pid);
  assert.equal(refreshed.runtimeIdentity.serverInstanceId, proof.endpoint.serverInstanceId);
  assert.ok(refreshed.identities.has(owner.pid));
  assert.throws(() => revalidate({ freeze: undefined }), /shared_runtime_freeze_incomplete/u);
  assert.throws(
    () => revalidate({ snapshot: [{ ...owner, creationDate: "2026-09-29T01:00:02.000Z" }] }),
    /shared_runtime_owner_identity_changed/u,
  );
  assert.throws(
    () => revalidate({ endpoint: { ...proof.endpoint, serverInstanceId: "srv_replaced" } }),
    /shared_runtime_endpoint_binding_changed/u,
  );
  assert.throws(
    () =>
      revalidate({
        endpoint: { ...proof.endpoint, runtimeGeneration: proof.endpoint.runtimeGeneration + 1 },
      }),
    /shared_runtime_endpoint_binding_changed/u,
  );

  const wrongPathOwner = runtimeSidecarProcess(
    fixture,
    { parentPid: fixture.launcher.pid },
    { data: win32.join(fixture.directories.home, "other-data") },
  );
  assert.throws(
    () =>
      assertFrozenRuntimeBinding({
        freeze: { ...freeze, ownerIdentity: wrongPathOwner },
        directories: fixture.directories,
        expectedExecutableNames: ["java.exe"],
        snapshot: [wrongPathOwner],
        endpoint: proof.endpoint,
        listeners: [
          { ownerPid: wrongPathOwner.pid, address: proof.endpoint.host, port: proof.endpoint.port },
        ],
      }),
    /shared_runtime_directory_roles_changed/u,
  );
  assert.throws(() => revalidate({ listeners: [] }), /shared_runtime_listener_binding_changed/u);

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const cleanupStart = source.indexOf("async function cleanupPhase(");
  const cleanupEnd = source.indexOf("\n}\n", cleanupStart);
  const cleanup = source.slice(cleanupStart, cleanupEnd);
  const confirmedExit = cleanup.indexOf("} else if (taskNormalExitConfirmed) {");
  const launcherBoundFallback = cleanup.indexOf("const bindRunLevel =", confirmedExit);
  assert.ok(confirmedExit >= 0 && launcherBoundFallback > confirmedExit);
  assert.match(
    cleanup.slice(confirmedExit, launcherBoundFallback),
    /revalidateSharedRuntimeFreezeAfterLauncherExit\(/u,
  );
  assert.doesNotMatch(
    cleanup.slice(confirmedExit, launcherBoundFallback),
    /freezeSharedRuntimeForCleanup\(/u,
  );
});

/** 共享证明必须保持 profile、run、endpoint、元数据与完整进程身份一致，PID 相等不是证明。 */
test("rejects changed shared bindings, malformed metadata and reused shared PID", () => {
  const fixture = runtimeIsolationFixture();
  const shared = runtimeSidecarProcess(fixture, { parentPid: 2 });
  const proof = runtimeEndpointProof(fixture, shared);
  const invalidProofs = [
    { ...proof, profileDirectory: `${fixture.directories.settings}-sibling` },
    { ...proof, runDirectory: `${fixture.directories.runtime}-sibling` },
    { ...proof, endpointPath: win32.join(fixture.directories.runtime, "other.endpoint.json") },
    { ...proof, endpoint: { ...proof.endpoint, host: "localhost" } },
    { ...proof, endpoint: { ...proof.endpoint, port: 0 } },
    { ...proof, endpoint: { ...proof.endpoint, runtimeGeneration: 0 } },
    { ...proof, ownerIdentity: { ...shared, creationDate: "2026-09-29T00:59:00.000Z" } },
    { ...proof, ownerIdentity: { ...shared, commandLine: `${shared.commandLine} --changed` } },
  ];
  for (const invalidProof of invalidProofs) {
    assert.throws(
      () =>
        assertRuntimeIsolation(
          [fixture.launcher, shared],
          fixture.directories,
          fixture.nativeScope,
          ["java.exe"],
          invalidProof,
        ),
      /endpoint_owner_identity_changed/u,
    );
  }
});

/** endpoint 证明不能为另一匹配 sidecar 背书，launcher 后代与 endpoint owner 必须选中同一身份。 */
test("rejects an endpoint proof disagreeing with the launcher sidecar", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture);
  const endpointOwner = runtimeSidecarProcess(
    fixture,
    { pid: 23, parentPid: 2 },
    {
      data: `${fixture.directories.data}-different`,
    },
  );
  assert.throws(
    () =>
      assertRuntimeIsolation(
        [fixture.launcher, owned, endpointOwner],
        fixture.directories,
        fixture.nativeScope,
        ["java.exe"],
        runtimeEndpointProof(fixture, endpointOwner),
      ),
    /launcher_endpoint_owner_disagree/u,
  );
});

/** 只允许 Windows 同义路径变化；扩展盘符、UNC、大小写与分隔符不应误判为另一隔离根。 */
test("accepts Windows extended-path, UNC and separator equivalents", () => {
  for (const root of [
    "C:\\temp\\ja-desktop-e2e-unit-1",
    "\\\\server\\share\\ja-desktop-e2e-unit-1",
  ]) {
    const fixture = runtimeIsolationFixture(root);
    const alternatePath = (value) =>
      value.startsWith("\\\\")
        ? `\\\\?\\UNC\\${value.slice(2).toUpperCase()}\\`
        : `\\\\?\\${value.toUpperCase().replaceAll("\\", "/")}/`;
    const owned = runtimeSidecarProcess(
      fixture,
      {},
      {
        home: alternatePath(fixture.directories.home),
        data: alternatePath(fixture.directories.data),
        run: alternatePath(fixture.directories.runtime),
        log: alternatePath(win32.join(fixture.directories.home, "logs", "java")),
      },
    );
    assert.equal(
      assertRuntimeIsolation([fixture.launcher, owned], fixture.directories, fixture.nativeScope)
        .appServerPid,
      owned.pid,
    );
  }
});

/** Native 名称需由最终 sidecar artifact 显式传入，不能把同名 Java fallback 当作最终产物。 */
test("requires the configured native executable identity", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture, { name: "JA-APP-SERVER.EXE" });
  assert.throws(
    () =>
      assertRuntimeIsolation([fixture.launcher, owned], fixture.directories, fixture.nativeScope),
    /owner_not_found/u,
  );
  assert.equal(
    assertRuntimeIsolation([fixture.launcher, owned], fixture.directories, fixture.nativeScope, [
      "ja-app-server.exe",
    ]).appServerPid,
    owned.pid,
  );
});

/** 参数的唯一性与 canonical 编码需要一起验证，错误参数不能被另一个合法副本掩盖。 */
test("rejects duplicate, malformed and incomplete sidecar arguments with scoped facts", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture);
  const dataArgument = /--data-dir-base64=[^\s]+/u.exec(owned.commandLine)[0];
  for (const commandLine of [
    `${owned.commandLine} ${dataArgument}`,
    `${owned.commandLine} --data-dir-base64`,
    `${owned.commandLine} --data-dir-base64=***`,
    owned.commandLine.replace(dataArgument, `${dataArgument}=`),
    owned.commandLine.replace(dataArgument, '"' + dataArgument),
    owned.commandLine.replace(dataArgument, "--data-dir-base64="),
    owned.commandLine.replace(/--log-dir-base64=[^\s]+/u, ""),
  ]) {
    assert.throws(
      () =>
        assertRuntimeIsolation(
          [fixture.launcher, { ...owned, commandLine }],
          fixture.directories,
          fixture.nativeScope,
        ),
      (error) => {
        assert.match(error.message, /owner_not_found/u);
        assert.match(error.message, /"pid":22/u);
        assert.match(error.message, /"arguments":\{/u);
        assert.equal(error.message.includes("commandLine"), false);
        return true;
      },
    );
  }
});

/** 成对的整体参数引号是 Windows 合法表示，不应为防止残缺引号而拒绝正常 Host 命令。 */
test("accepts quoted canonical sidecar arguments", () => {
  const fixture = runtimeIsolationFixture();
  const owned = runtimeSidecarProcess(fixture);
  owned.commandLine = owned.commandLine.replace(/--\w+-dir-base64=[^\s]+/gu, '"$&"');
  assert.doesNotThrow(() =>
    assertRuntimeIsolation([fixture.launcher, owned], fixture.directories, fixture.nativeScope),
  );
});

/** 恢复 fixture 同时冻结启动回执、RPC generation、私有 endpoint 与完整进程身份，避免单独 Ready 冒充恢复。
 * @author kongweiguang
 */
function runtimeRestartFixture() {
  const fixture = runtimeIsolationFixture();
  const previousIdentity = runtimeSidecarProcess(fixture);
  const restoredIdentity = runtimeSidecarProcess(fixture, {
    pid: 23,
    creationDate: "2026-09-29T01:00:02.000Z",
  });
  const directoryRoles = {
    home: fixture.directories.home,
    data: fixture.directories.data,
    run: fixture.directories.runtime,
    log: win32.join(fixture.directories.home, "logs", "java"),
  };
  const endpoint = {
    ...runtimeEndpointProof(fixture, previousIdentity).endpoint,
    ownerPid: previousIdentity.pid,
  };
  return {
    previousIsolation: {
      appServerIdentity: previousIdentity,
      endpoint,
      decodedDirectories: directoryRoles,
    },
    restoredIsolation: {
      appServerIdentity: restoredIdentity,
      endpoint: {
        ...endpoint,
        ownerPid: restoredIdentity.pid,
        serverInstanceId: "srv_restarted",
        runtimeGeneration: 2,
      },
      decodedDirectories: { ...directoryRoles },
    },
    previousRuntimeState: {
      status: "ready",
      generation: endpoint.runtimeGeneration,
      serverInstanceId: endpoint.serverInstanceId,
    },
    restoredRuntimeState: {
      status: "ready",
      generation: 2,
      serverInstanceId: "srv_restarted",
    },
    startLifecycle: [{ phase: "start" }, { phase: "resolved" }],
    previousOwnerExit: {
      status: "exited",
      ownerIdentity: previousIdentity,
    },
  };
}

/** 用户只点击一次真实控件，且 Host、进程与服务身份都更新后才可记录成功恢复。 */
test("accepts a single explicit Runtime reconnect with a new process and server identity", () => {
  const evidence = assertRuntimeRestartEvidence(runtimeRestartFixture());
  assert.equal(evidence.status, "passed");
  assert.equal(evidence.processIdentityChanged, true);
  assert.equal(evidence.serverInstanceChanged, true);
  assert.equal(evidence.generationAdvanced, true);
  assert.equal(evidence.previousOwnerExited, true);
  assert.equal(evidence.endpointOwnersVerified, true);
  assert.equal(evidence.runtimeDirectoryRolesVerified, true);
  assert.equal(evidence.startCount, 1);
  assert.equal(JSON.stringify(evidence).includes("commandLine"), false);
});

/** 转交要求当前 freeze 仍绑定源 epoch；提交一次后旧身份不能再次成为转交源。 */
test("allows one Runtime epoch handoff and rejects a second rebind from the old owner", () => {
  const fixture = runtimeRestartFixture();
  const currentFreeze = {
    epoch: 7,
    endpoint: fixture.previousIsolation.endpoint,
    runtimeIdentity: fixture.previousRuntimeState,
    ownerIdentity: fixture.previousIsolation.appServerIdentity,
    identities: new Map([
      [
        fixture.previousIsolation.appServerIdentity.pid,
        fixture.previousIsolation.appServerIdentity,
      ],
    ]),
  };
  const restartEvidence = assertRuntimeRestartEvidence(fixture);
  const boundary = {
    currentFreeze,
    previousIsolation: fixture.previousIsolation,
    restoredIsolation: fixture.restoredIsolation,
    previousRuntimeState: fixture.previousRuntimeState,
    restoredRuntimeState: fixture.restoredRuntimeState,
    previousOwnerExit: fixture.previousOwnerExit,
    restartEvidence,
  };
  assert.deepEqual(assertRuntimeHandoffBoundary(boundary), { fromEpoch: 7, toEpoch: 8 });

  const reboundFreeze = {
    ...currentFreeze,
    epoch: 8,
    endpoint: fixture.restoredIsolation.endpoint,
    runtimeIdentity: fixture.restoredRuntimeState,
    ownerIdentity: fixture.restoredIsolation.appServerIdentity,
  };
  assert.throws(
    () => assertRuntimeHandoffBoundary({ ...boundary, currentFreeze: reboundFreeze }),
    /runtime_handoff_source_epoch_changed/u,
  );
});

/** 独立 Tauri Host 各自从 generation 1 启动；只在旧 owner 确认 absent 后按新 server 实例 handoff。
 * @author kongweiguang
 */
test("allows an independent phase handoff with the same generation and a new server instance", () => {
  const fixture = runtimeRestartFixture();
  const restoredIsolation = {
    ...fixture.restoredIsolation,
    endpoint: {
      ...fixture.restoredIsolation.endpoint,
      port: fixture.restoredIsolation.endpoint.port + 1,
      runtimeGeneration: fixture.previousRuntimeState.generation,
      serverInstanceId: "srv_independent_phase",
    },
    decodedDirectories: { ...fixture.previousIsolation.decodedDirectories },
  };
  const restoredRuntimeState = {
    status: "ready",
    generation: fixture.previousRuntimeState.generation,
    serverInstanceId: restoredIsolation.endpoint.serverInstanceId,
  };
  const previousOwnerExit = {
    ...fixture.previousOwnerExit,
    ownerPresence: "absent",
    previousEndpointListenerGone: true,
  };
  const evidence = assertRuntimePhaseHandoffEvidence({
    ...fixture,
    restoredIsolation,
    restoredRuntimeState,
    previousOwnerExit,
  });

  assert.equal(evidence.scope, "independent_host_phase");
  assert.equal(evidence.serverInstanceChanged, true);
  assert.equal(evidence.generationComparedWithinEachServer, true);
  assert.equal(evidence.previousOwnerExited, true);
  assert.deepEqual(
    assertRuntimeHandoffBoundary({
      currentFreeze: {
        epoch: 7,
        endpoint: fixture.previousIsolation.endpoint,
        runtimeIdentity: fixture.previousRuntimeState,
        ownerIdentity: fixture.previousIsolation.appServerIdentity,
        identities: new Map([
          [
            fixture.previousIsolation.appServerIdentity.pid,
            fixture.previousIsolation.appServerIdentity,
          ],
        ]),
      },
      previousIsolation: fixture.previousIsolation,
      restoredIsolation,
      previousRuntimeState: fixture.previousRuntimeState,
      restoredRuntimeState,
      previousOwnerExit,
      restartEvidence: evidence,
    }),
    { fromEpoch: 7, toEpoch: 8 },
  );

  assert.throws(
    () =>
      assertRuntimeReadyBinding(
        {
          runtimeIdentity: fixture.previousRuntimeState,
          endpoint: fixture.previousIsolation.endpoint,
          ownerIdentity: fixture.previousIsolation.appServerIdentity,
        },
        restoredRuntimeState,
        restoredIsolation.endpoint,
        restoredIsolation.appServerIdentity,
      ),
    /native_runtime_binding_changed/u,
  );
  assert.throws(
    () =>
      assertRuntimeRestartEvidence({
        ...fixture,
        restoredIsolation,
        restoredRuntimeState,
      }),
    /显式重连证据不完整/u,
  );
});

/** 未知、同 PID 存活或 PID 已复用都不是旧 owner 明确退出，不能被新 Ready 取代。
 * @author kongweiguang
 */
test("rejects independent phase handoff when the previous owner is live, reused, or unverified", () => {
  const fixture = runtimeRestartFixture();
  const restoredIsolation = {
    ...fixture.restoredIsolation,
    endpoint: {
      ...fixture.restoredIsolation.endpoint,
      runtimeGeneration: fixture.previousRuntimeState.generation,
      serverInstanceId: "srv_independent_phase",
    },
  };
  const restoredRuntimeState = {
    status: "ready",
    generation: fixture.previousRuntimeState.generation,
    serverInstanceId: restoredIsolation.endpoint.serverInstanceId,
  };
  for (const ownerPresence of ["same", "incomplete", "reused", undefined]) {
    assert.throws(
      () =>
        assertRuntimePhaseHandoffEvidence({
          ...fixture,
          restoredIsolation,
          restoredRuntimeState,
          previousOwnerExit: {
            status: "exited",
            ownerIdentity: fixture.previousIsolation.appServerIdentity,
            ownerPresence,
            previousEndpointListenerGone: true,
          },
        }),
      /独立 Host phase handoff 证据不完整/u,
    );
  }
  assert.throws(
    () =>
      assertRuntimePhaseHandoffEvidence({
        ...fixture,
        restoredIsolation,
        restoredRuntimeState,
        previousOwnerExit: {
          status: "exited",
          ownerIdentity: fixture.previousIsolation.appServerIdentity,
          ownerPresence: "absent",
          previousEndpointListenerGone: false,
        },
      }),
    /独立 Host phase handoff 证据不完整/u,
  );
  assert.throws(
    () =>
      assertRuntimePhaseHandoffEvidence({
        ...fixture,
        restoredIsolation: {
          ...restoredIsolation,
          endpoint: { ...restoredIsolation.endpoint, ownerPid: 999 },
        },
        restoredRuntimeState,
        previousOwnerExit: {
          status: "exited",
          ownerIdentity: fixture.previousIsolation.appServerIdentity,
          ownerPresence: "absent",
          previousEndpointListenerGone: true,
        },
      }),
    /独立 Host phase handoff 证据不完整/u,
  );
});

/** 生产提交器须将 Prime/旧 epoch 历史和恢复 owner 子树合并到下一代 freeze 与退出历史。 */
test("commits a Runtime handoff without losing Prime or previous-epoch identities", () => {
  const fixture = runtimeRestartFixture();
  const primeOwner = {
    pid: 71_001,
    parentPid: 1,
    name: "java.exe",
    commandLine: "java -Dphase=prime",
    creationDate: "2026-10-05T10:00:00.000Z",
  };
  const primeChild = {
    pid: 71_002,
    parentPid: primeOwner.pid,
    name: "java.exe",
    commandLine: "java -Dphase=prime-worker",
    creationDate: "2026-10-05T10:00:01.000Z",
  };
  const previousIdentity = fixture.previousIsolation.appServerIdentity;
  const restoredIdentity = fixture.restoredIsolation.appServerIdentity;
  const currentFreeze = {
    epoch: 4,
    endpoint: fixture.previousIsolation.endpoint,
    runtimeIdentity: fixture.previousRuntimeState,
    ownerIdentity: previousIdentity,
    identities: new Map([[previousIdentity.pid, previousIdentity]]),
  };
  const restoredChild = {
    ...restoredIdentity,
    pid: 71_003,
    parentPid: restoredIdentity.pid,
    commandLine: `${restoredIdentity.commandLine} -Dchild=true`,
  };
  const committed = createRuntimeFreezeHandoff({
    currentFreeze,
    restoredIsolation: fixture.restoredIsolation,
    restoredRuntimeState: fixture.restoredRuntimeState,
    restoredTree: new Map([
      [restoredIdentity.pid, restoredIdentity],
      [restoredChild.pid, restoredChild],
    ]),
    history: mergeRuntimeIdentityHistory(undefined, [primeOwner, primeChild, previousIdentity]),
    boundary: { fromEpoch: 4, toEpoch: 5 },
  });

  assert.equal(committed.freeze.epoch, 5);
  assert.equal(committed.freeze.ownerIdentity.pid, restoredIdentity.pid);
  assert.deepEqual(
    [...committed.history.values()].map(({ pid }) => pid).sort((left, right) => left - right),
    [22, 23, 71_001, 71_002, 71_003],
  );
});

/** 显式交接前直接复验新 native server ID 与 generation，不能接受普通 Ready 漂移。 */
test("rejects wrong native server identity or generation at the Runtime handoff boundary", () => {
  const fixture = runtimeRestartFixture();
  const restartEvidence = assertRuntimeRestartEvidence(fixture);
  const boundary = {
    currentFreeze: {
      epoch: 4,
      endpoint: fixture.previousIsolation.endpoint,
      runtimeIdentity: fixture.previousRuntimeState,
      ownerIdentity: fixture.previousIsolation.appServerIdentity,
      identities: new Map(),
    },
    previousIsolation: fixture.previousIsolation,
    restoredIsolation: fixture.restoredIsolation,
    previousRuntimeState: fixture.previousRuntimeState,
    restoredRuntimeState: fixture.restoredRuntimeState,
    previousOwnerExit: fixture.previousOwnerExit,
    restartEvidence,
  };
  for (const restoredRuntimeState of [
    { ...fixture.restoredRuntimeState, serverInstanceId: "srv_wrong" },
    { ...fixture.restoredRuntimeState, generation: 1 },
  ]) {
    assert.throws(
      () => assertRuntimeHandoffBoundary({ ...boundary, restoredRuntimeState }),
      /runtime_handoff_target_identity_incomplete/u,
    );
  }
});

/** 恢复回执不得用新 Ready 掩盖旧进程仍存活、退出证据缺失或 owner 身份串线。 */
test("rejects Runtime handoff while the previous owner is alive or its exit proof is mismatched", () => {
  const fixture = runtimeRestartFixture();
  for (const previousOwnerExit of [
    undefined,
    { status: "still_running", ownerIdentity: fixture.previousIsolation.appServerIdentity },
    { status: "exited", ownerIdentity: fixture.restoredIsolation.appServerIdentity },
  ]) {
    assert.throws(
      () => assertRuntimeRestartEvidence({ ...fixture, previousOwnerExit }),
      /App Server 显式重连证据不完整/u,
    );
  }
});

/** Windows 可以复用 PID；完整创建身份与新服务实例都已确认时，不应误判成未启动新进程。 */
test("accepts a reused PID only when the complete process and endpoint identity changed", () => {
  const fixture = runtimeRestartFixture();
  fixture.restoredIsolation.appServerIdentity.pid = fixture.previousIsolation.appServerIdentity.pid;
  fixture.restoredIsolation.endpoint.ownerPid = fixture.restoredIsolation.appServerIdentity.pid;
  assert.doesNotThrow(() => assertRuntimeRestartEvidence(fixture));
});

/** 任一启动缺失、重复、失败或未结算都不构成显式恢复，不能把后续 Ready 作为替代证据。 */
test("rejects missing, duplicate, failed and unsettled Runtime starts", () => {
  const fixture = runtimeRestartFixture();
  for (const startLifecycle of [
    undefined,
    [],
    [{ phase: "start" }],
    [{ phase: "resolved" }],
    [{ phase: "start" }, { phase: "start" }, { phase: "resolved" }],
    [{ phase: "start" }, { phase: "resolved" }, { phase: "resolved" }],
    [{ phase: "start" }, { phase: "resolved" }, { phase: "rejected" }],
  ]) {
    assert.throws(
      () => assertRuntimeRestartEvidence({ ...fixture, startLifecycle }),
      /显式重连证据不完整/u,
    );
  }
});

/** 保留旧进程、旧 server、缺失完整身份或与私有 endpoint 不一致均不能作为强杀恢复成功。
 * @author kongweiguang
 */
test("rejects unchanged or unverified App Server restart identities", () => {
  const fixture = runtimeRestartFixture();
  for (const restoredIsolation of [
    {
      ...fixture.restoredIsolation,
      appServerIdentity: fixture.previousIsolation.appServerIdentity,
    },
    { ...fixture.restoredIsolation, endpoint: fixture.previousIsolation.endpoint },
    {
      ...fixture.restoredIsolation,
      appServerIdentity: { ...fixture.restoredIsolation.appServerIdentity, commandLine: "" },
    },
    { ...fixture.restoredIsolation, endpoint: undefined },
  ]) {
    assert.throws(
      () => assertRuntimeRestartEvidence({ ...fixture, restoredIsolation }),
      /显式重连证据不完整/u,
    );
  }
});

/** 新 sidecar 仍须通过权威 Ready 和递增 RPC generation；connecting 或身份不匹配不能放行任务。
 * @author kongweiguang
 */
test("rejects non-ready or stale authoritative Runtime generations", () => {
  const fixture = runtimeRestartFixture();
  for (const restoredRuntimeState of [
    { ...fixture.restoredRuntimeState, status: "crashed" },
    { ...fixture.restoredRuntimeState, status: "starting" },
    { ...fixture.restoredRuntimeState, serverInstanceId: "" },
    { ...fixture.restoredRuntimeState, serverInstanceId: "srv_other" },
    { ...fixture.restoredRuntimeState, generation: 1 },
    { ...fixture.restoredRuntimeState, generation: 0 },
  ]) {
    assert.throws(
      () => assertRuntimeRestartEvidence({ ...fixture, restoredRuntimeState }),
      /显式重连证据不完整/u,
    );
  }
  assert.throws(
    () =>
      assertRuntimeRestartEvidence({
        ...fixture,
        previousRuntimeState: { ...fixture.previousRuntimeState, serverInstanceId: "srv_other" },
      }),
    /显式重连证据不完整/u,
  );
});

/**
 * 复用现有严格 CSS locator 与 JSDOM 页面 realm，只控制首次 open 的 DOM 投影和 native state 返回时序。
 * 不生成假的 owner 或 page identity；替换与错误都通过真实 DOM 属性和节点让生产回调观察。
 */
function authoritativePreviewFixture() {
  const dom = new JSDOM(
    '<section data-thread-workbench-scope="ws:preview-thread"><div class="ja-workbench"><div class="ja-preview-viewport" data-preview-page-id="" data-preview-page-loading="false"></div></div></section>',
    {
      runScripts: "outside-only",
    },
  );
  const document = dom.window.document;
  const host = document.querySelector("[data-thread-workbench-scope]");
  const viewport = document.querySelector(".ja-preview-viewport");
  const page = retainedWorkbenchPage(document);
  const workbench = page.locator(".ja-workbench");
  const state = {
    snapshot: {
      id: "preview-native-one",
      generation: 7,
      status: "open",
      load_status: "finished",
      url: "http://127.0.0.1:49152/",
    },
    calls: [],
    bindingReads: 0,
    emptyObserved: Promise.withResolvers(),
    nativeObserved: Promise.withResolvers(),
    onState: undefined,
  };
  const evaluate = workbench.evaluate;
  /** 保留既有 locator 的 strict 数量检查，实际生产 callback 在浏览器 realm 执行。 */
  workbench.evaluate = async (callback, argument) => {
    state.bindingReads += 1;
    const value = await evaluate((element) =>
      dom.window.Function(
        "element",
        "argument",
        "return (" + callback.toString() + ")(element, argument);",
      )(element, argument),
    );
    if (!viewport.getAttribute("data-preview-page-id")) state.emptyObserved.resolve();
    return value;
  };
  const locate = workbench.locator;
  /** 属性读取也走真实 DOM，使旧的一次性空 id 拒绝能被同一行为回归暴露。 */
  workbench.locator = (selector) => {
    const locator = locate(selector);
    /** 不补默认 id；空属性仍作为首次创建的真实观测返回。 */
    locator.getAttribute = async (name) => {
      const value = await locator.evaluate((element) => element.getAttribute(name));
      if (name === "data-preview-page-id" && !value) state.emptyObserved.resolve();
      return value;
    };
    return locator;
  };
  dom.window.__TAURI_INTERNALS__ = {
    /** 只替代 IPC 返回，不代替 helper 的 DOM、owner 或权威状态判断。 */
    invoke: async (command, input) => {
      assert.equal(command, "ja_preview_state");
      state.calls.push(input.input.sessionId);
      state.nativeObserved.resolve();
      await state.onState?.();
      return state.snapshot;
    },
  };
  return { dom, host, viewport, page, workbench, state };
}

/** 首次 open 已签发 page id 后若目标页直接失败，失败属于当前唯一页面且应立即结束等待。 */
test("rejects an initial Preview load failure after its page identity is published", async () => {
  const { dom, viewport, page, workbench, state } = authoritativePreviewFixture();
  try {
    state.snapshot = { ...state.snapshot, generation: 8, load_status: "failed" };
    viewport.setAttribute("data-preview-page-id", state.snapshot.id);
    viewport.setAttribute("data-preview-page-loading", "false");
    const error = dom.window.document.createElement("div");
    error.className = "ja-preview-recovery-row";
    viewport.before(error);
    await assert.rejects(
      waitForAuthoritativePreviewState(
        page,
        workbench,
        { url: state.snapshot.url, loadStatus: "finished" },
        Date.now() + 2_000,
      ),
      /Preview navigation failed while a finished page was expected/u,
    );
    assert.deepEqual(state.calls, [state.snapshot.id]);
  } finally {
    dom.window.close();
  }
});

/** 同一 generation 的历史失败不能终止新导航；只在新 generation 成功后完成本次等待。 */
test("ignores a stale Preview failure until the requested navigation generation completes", async () => {
  const { dom, viewport, page, workbench, state } = authoritativePreviewFixture();
  const baseline = {
    ownerScope: "ws:preview-thread",
    pageId: state.snapshot.id,
    generation: state.snapshot.generation,
  };
  state.snapshot = { ...state.snapshot, load_status: "failed" };
  viewport.setAttribute("data-preview-page-id", state.snapshot.id);
  viewport.setAttribute("data-preview-page-loading", "false");
  const error = dom.window.document.createElement("div");
  error.className = "ja-preview-recovery-row";
  viewport.before(error);
  let reads = 0;
  state.onState = async () => {
    reads += 1;
    if (reads === 2) {
      state.snapshot = {
        ...state.snapshot,
        generation: baseline.generation + 1,
        load_status: "finished",
      };
      viewport.setAttribute("data-preview-page-loading", "false");
      error.remove();
    }
  };
  let settled = false;
  const pending = waitForAuthoritativePreviewState(
    page,
    workbench,
    { url: state.snapshot.url, loadStatus: "finished", baseline },
    Date.now() + 2_000,
  );
  pending.then(
    () => {
      settled = true;
    },
    () => {
      settled = true;
    },
  );
  try {
    await state.nativeObserved.promise;
    await Promise.resolve();
    assert.equal(settled, false);
    const snapshot = await pending;
    assert.equal(snapshot.generation, baseline.generation + 1);
    assert.ok(state.calls.length >= 2);
  } finally {
    await pending.catch(() => {});
    dom.window.close();
  }
});

/** 新 generation 对应目标 URL 的真实失败必须立即拒绝 finished 期望，不能等到总 deadline。 */
test("rejects a target Preview failure from the new navigation generation immediately", async () => {
  const { dom, viewport, page, workbench, state } = authoritativePreviewFixture();
  const baseline = {
    ownerScope: "ws:preview-thread",
    pageId: state.snapshot.id,
    generation: state.snapshot.generation,
  };
  try {
    state.snapshot = {
      ...state.snapshot,
      generation: baseline.generation + 1,
      load_status: "failed",
    };
    viewport.setAttribute("data-preview-page-id", state.snapshot.id);
    viewport.setAttribute("data-preview-page-loading", "false");
    const error = dom.window.document.createElement("div");
    error.className = "ja-preview-recovery-row";
    viewport.before(error);
    await assert.rejects(
      waitForAuthoritativePreviewState(
        page,
        workbench,
        { url: state.snapshot.url, loadStatus: "finished", baseline },
        Date.now() + 2_000,
      ),
      /Preview navigation failed while a finished page was expected/u,
    );
    assert.deepEqual(state.calls, [state.snapshot.id]);
  } finally {
    dom.window.close();
  }
});

/** 首次 native open 前不能误报空 id；取得 id 后仍等待真实加载终态及 React 投影，负向加载保留。 */
test("waits for a deferred Preview page identity before checking authoritative state", async () => {
  for (const loadStatus of ["finished", "failed"]) {
    const { dom, viewport, page, workbench, state } = authoritativePreviewFixture();
    const controller = new AbortController();
    const open = Promise.withResolvers();
    /** deferred open 只发布 Rust 签发的 id，初始 loading 仍不能满足权威完成条件。 */
    const published = open.promise.then(() => {
      state.snapshot = { ...state.snapshot, load_status: "loading" };
      viewport.setAttribute("data-preview-page-id", state.snapshot.id);
      viewport.setAttribute("data-preview-page-loading", "true");
    });
    let settled = false;
    const pending = waitForAuthoritativePreviewState(
      page,
      workbench,
      { url: state.snapshot.url, loadStatus },
      Date.now() + 2_000,
      controller.signal,
    );
    pending.then(
      () => {
        settled = true;
      },
      () => {
        settled = true;
      },
    );
    try {
      await state.emptyObserved.promise;
      await Promise.resolve();
      assert.equal(settled, false);
      assert.equal(state.calls.length, 0);
      open.resolve();
      await published;
      await state.nativeObserved.promise;
      assert.equal(settled, false);
      state.snapshot = { ...state.snapshot, load_status: loadStatus };
      viewport.setAttribute("data-preview-page-loading", "false");
      if (loadStatus === "failed") {
        const error = dom.window.document.createElement("div");
        error.className = "ja-preview-recovery-row";
        error.textContent = "预览加载失败，请重试。";
        viewport.before(error);
      }
      const snapshot = await pending;
      assert.deepEqual(
        { ...snapshot },
        {
          id: "preview-native-one",
          generation: 7,
          status: "open",
          loadStatus,
          url: state.snapshot.url,
          renderedLoading: "false",
        },
      );
      assert.ok(state.calls.length >= 1);
      assert.ok(state.calls.every((sessionId) => sessionId === "preview-native-one"));
    } finally {
      controller.abort(new Error("Preview fixture cleanup"));
      await pending.catch(() => {});
      dom.window.close();
    }
  }
});

/** 创建前的验证或 native open/recovery 错误必须当轮拒绝，不能一直等不存在的 page id。 */
test("rejects a real Preview open error before a page identity is published", async () => {
  for (const className of ["ja-preview-error", "ja-preview-recovery-row"]) {
    const { dom, viewport, page, workbench, state } = authoritativePreviewFixture();
    try {
      const error = dom.window.document.createElement("div");
      error.className = className;
      error.textContent = "无法打开此页面，请重试。";
      viewport.before(error);
      await assert.rejects(
        waitForAuthoritativePreviewState(
          page,
          workbench,
          { url: state.snapshot.url, loadStatus: "finished" },
          Date.now() + 2_000,
        ),
        /Preview 页面创建失败/u,
      );
      assert.equal(state.bindingReads, 1);
      assert.equal(state.calls.length, 0);
    } finally {
      dom.window.close();
    }
  }
});

/** native 返回期间改 owner 或 page 必须拒绝原观测，不能重新认领新目标并拿其状态冒充成功。 */
test("rejects Preview owner or page replacement during authoritative observation", async () => {
  for (const replacement of ["owner", "page"]) {
    const { dom, host, viewport, page, workbench, state } = authoritativePreviewFixture();
    try {
      viewport.setAttribute("data-preview-page-id", state.snapshot.id);
      /** 变更发生在 IPC 已收到原 sessionId 后，检验 await 返回后的身份栅栏。 */
      state.onState = async () => {
        if (replacement === "owner")
          host.setAttribute("data-thread-workbench-scope", "ws:other-thread");
        else viewport.setAttribute("data-preview-page-id", "preview-native-replacement");
      };
      await assert.rejects(
        waitForAuthoritativePreviewState(
          page,
          workbench,
          { url: state.snapshot.url, loadStatus: "finished" },
          Date.now() + 2_000,
        ),
        replacement === "owner" ? /Preview 所属 Thread 已变化/u : /Preview page identity 已变化/u,
      );
      assert.deepEqual(state.calls, ["preview-native-one"]);
    } finally {
      dom.window.close();
    }
  }
});

/**
 * 复用严格 CSS locator 和真实 JSDOM realm，以地址键盘提交驱动窄 IPC stub；不复制 driver 的验收判断。
 * native 拒绝、重试和故障注入只改变实际 DOM、快照与 probe，使回归能检查完整负向路径及身份边界。
 * State 回调只控制权威 IPC 的返回时序，driver 仍负责判断 native 状态与 DOM 投影是否收敛。
 * @author kongweiguang
 */
function previewDirectoryRejectionFixture() {
  const fixture = authoritativePreviewFixture();
  const { dom, host, viewport, page, workbench, state } = fixture;
  const document = dom.window.document;
  const inspector = document.createElement("aside");
  inspector.className = "ja-inspector";
  inspector.setAttribute("aria-label", "工作区面板");
  host.append(inspector);
  inspector.append(document.querySelector(".ja-workbench"));
  const input = document.createElement("input");
  input.setAttribute("aria-label", "浏览器地址");
  viewport.before(input);
  const childDom = new JSDOM("<main>JA_PREVIEW_OK</main>", { runScripts: "outside-only" });
  const directoryTarget = "file:///C:/isolated-ja-workspace/";
  state.snapshot = { ...state.snapshot, can_go_back: false, can_go_forward: false };
  viewport.setAttribute("data-preview-page-id", state.snapshot.id);
  viewport.setAttribute("data-preview-page-url", state.snapshot.url);
  input.value = state.snapshot.url;
  state.commands = [];
  state.submissions = [];
  state.requests = 0;
  state.keepError = false;
  state.fileCommand = "ja_preview_navigate_file";
  /** 每个 callback 在对应浏览器 realm 执行，生产 globalThis/activeElement 判断不由 fixture 代答。 */
  page.evaluate = async (callback, argument) =>
    dom.window.Function("argument", "return (" + callback.toString() + ")(argument);")(argument);
  /** 只扩展本场景实际使用的 Locator 操作；元素数量、文本、草稿与焦点全部从真实 DOM 回读。 */
  const decorate = (locator) => {
    const evaluate = locator.evaluate;
    const locate = locator.locator;
    const decorated = {
      ...locator,
      locator: (selector) => decorate(locate(selector)),
      evaluate: async (callback, argument) =>
        evaluate((element) =>
          dom.window.Function(
            "element",
            "argument",
            "return (" + callback.toString() + ")(element, argument);",
          )(element, argument),
        ),
      /** ARIA 名称仍转为实际 DOM selector，不向后台 owner 或任意第一个元素回退。 */
      getByRole: (role, options) => {
        assert.ok(role === "alert" || role === "textbox");
        if (role === "textbox") assert.equal(options.name, "浏览器地址");
        return decorate(
          locate(role === "alert" ? '[role="alert"]' : 'input[aria-label="浏览器地址"]'),
        );
      },
      /** 文案读取必须满足 exact；清错验收则要求对应 alert 正文真正卸载。 */
      getByText: (text, options) => {
        assert.equal(options.exact, true);
        const paragraph = decorate(locate("p"));
        return {
          waitFor: async ({ state: visibility, timeout }) => {
            assert.ok(timeout > 0);
            const count = await paragraph.count();
            if (visibility === "detached") assert.equal(count, 0);
            else {
              assert.equal(visibility, "visible");
              assert.equal(count, 1);
              assert.equal(await paragraph.evaluate((element) => element.textContent), text);
            }
          },
        };
      },
      /** 地址操作派发真实 DOM 事件，提交只由 Enter 处理器推进，不在观察方法中偷偷执行。 */
      fill: async (value) =>
        decorated.evaluate((element, next) => {
          element.value = next;
          element.dispatchEvent(new globalThis.Event("input", { bubbles: true }));
        }, value),
      inputValue: async () => decorated.evaluate((element) => element.value),
      focus: async () => {
        await decorated.evaluate((element) => element.focus());
        state.focusedDraft = input.value;
      },
      press: async (key) => {
        assert.equal(key, "Enter");
        await decorated.evaluate((element) =>
          element.dispatchEvent(
            new globalThis.KeyboardEvent("keydown", { key: "Enter", bubbles: true }),
          ),
        );
        await state.submission;
      },
    };
    return decorated;
  };
  const locate = page.locator;
  page.locator = (selector) => decorate(locate(selector));
  workbench.getByRole = (role, options) => {
    assert.equal(role, "textbox");
    assert.equal(options.name, "浏览器地址");
    return decorate(workbench.locator('input[aria-label="浏览器地址"]'));
  };
  /** State 读取保留真实 helper projection；显式导航则经过现有 invoke probe 记录完整 phase。 */
  dom.window.__TAURI_INTERNALS__.invoke = async (command, args) => {
    if (command === "ja_preview_state") {
      state.calls.push(args.input.sessionId);
      await state.onState?.();
      return state.snapshot;
    }
    const invoke = dom.window.__JA_E2E_NATIVE_INVOKE_PROBE__;
    return invoke({ command, args }, async () => {
      state.commands.push({ command, input: { ...args.input } });
      if (command === "ja_preview_navigate_file") throw new Error("file_is_directory");
      if (command === "ja_preview_navigate") {
        if (typeof args.input.url !== "string") throw new Error("native_http_input_invalid");
        state.snapshot = {
          ...state.snapshot,
          generation: state.snapshot.generation + 1,
          load_status: "finished",
          url: args.input.url,
          can_go_back: true,
        };
        viewport.setAttribute("data-preview-page-url", state.snapshot.url);
        state.requests += 1;
      }
      return state.snapshot;
    });
  };
  /** Fake native 只提供拒绝或成功结果；错误节点和重试投影均由显式提交产生，便于注入真实失效状态。 */
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    const target = input.value;
    state.submissions.push(target);
    state.submission = (async () => {
      const identity = { sessionId: state.snapshot.id, generation: state.snapshot.generation };
      try {
        await dom.window.__TAURI_INTERNALS__.invoke(
          target === directoryTarget ? state.fileCommand : "ja_preview_navigate",
          {
            input: {
              ...identity,
              ...(target === directoryTarget ? { target } : { url: target, source: "user" }),
            },
          },
        );
        if (!state.keepError) document.querySelector('[role="alert"]')?.remove();
        await state.onRetry?.();
      } catch {
        const error = document.createElement("div");
        error.className = "ja-preview-recovery-row";
        error.setAttribute("role", "alert");
        const paragraph = document.createElement("p");
        paragraph.textContent = "这是一个文件夹，无法在浏览器中打开。";
        error.append(paragraph);
        viewport.before(error);
        await state.onDirectoryRejected?.();
      }
    })();
  });
  const child = {
    isClosed: () => false,
    url: () => state.snapshot.url,
    evaluate: async (callback) =>
      childDom.window.Function("return (" + callback.toString() + ")();")(),
  };
  page.context = () => ({ pages: () => [page, child] });
  const previewFixture = { url: state.snapshot.url, requestCount: () => state.requests };
  return {
    ...fixture,
    childDom,
    input,
    directoryTarget,
    previewFixture,
    close: () => {
      dom.window.close();
      childDom.window.close();
    },
  };
}

/** 目录失败不能动已加载页面；用户改地址后只发一次同 page 导航，并在真实终态后清错。 */
test("keeps the loaded Preview page on directory rejection and retries once in the same page", async () => {
  const fixture = previewDirectoryRejectionFixture();
  const { page, workbench, directoryTarget, previewFixture, state } = fixture;
  try {
    assert.deepEqual(
      await exercisePreviewDirectoryRejection(
        page,
        workbench,
        directoryTarget,
        previewFixture,
        Date.now() + 2_000,
      ),
      { directoryRejected: true, retainedLoadedPage: true, recovered: true },
    );
    assert.equal(state.focusedDraft, directoryTarget);
    assert.deepEqual(state.submissions, [
      directoryTarget,
      previewFixture.url + "after-directory-error",
    ]);
    assert.deepEqual(
      state.commands.map(({ command, input }) => ({
        command,
        sessionId: input.sessionId,
        generation: input.generation,
      })),
      [
        { command: "ja_preview_navigate_file", sessionId: "preview-native-one", generation: 7 },
        { command: "ja_preview_navigate", sessionId: "preview-native-one", generation: 7 },
      ],
    );
  } finally {
    fixture.close();
  }
});

/** 真错误文案不能掩盖被拒绝操作的副作用；逐项变更原 owner/native/DOM/content，应在重试前拒绝。 */
test("rejects directory failure that changes Preview ownership, native state, or loaded content", async () => {
  const mutations = {
    owner: ({ host }) => host.setAttribute("data-thread-workbench-scope", "ws:other-thread"),
    page: ({ viewport }) => viewport.setAttribute("data-preview-page-id", "other-native-page"),
    nativeId: ({ state }) => {
      state.snapshot.id = "other-native-page";
    },
    generation: ({ state }) => {
      state.snapshot.generation += 1;
    },
    history: ({ state }) => {
      state.snapshot.can_go_back = true;
    },
    forwardHistory: ({ state }) => {
      state.snapshot.can_go_forward = true;
    },
    url: ({ state }) => {
      state.snapshot.url = "http://127.0.0.1:49152/unintended";
    },
    status: ({ state }) => {
      state.snapshot.status = "closed";
    },
    load: ({ state }) => {
      state.snapshot.load_status = "loading";
    },
    renderedUrl: ({ viewport }) => viewport.setAttribute("data-preview-page-url", "about:blank"),
    renderedLoading: ({ viewport }) => viewport.setAttribute("data-preview-page-loading", "true"),
    content: ({ childDom }) => {
      childDom.window.document.querySelector("main").textContent = "lost";
    },
  };
  for (const [name, mutate] of Object.entries(mutations)) {
    const fixture = previewDirectoryRejectionFixture();
    const { page, workbench, directoryTarget, previewFixture, state } = fixture;
    /** 变更位于 native 已明确拒绝之后，driver 必须检查保留状态，而不能凭错误文案通过。 */
    state.onDirectoryRejected = () => mutate(fixture);
    try {
      await assert.rejects(
        exercisePreviewDirectoryRejection(
          page,
          workbench,
          directoryTarget,
          previewFixture,
          Date.now() + 2_000,
        ),
        /Preview 目录拒绝改变了|Preview 目录拒绝丢失了原 native loaded 内容/u,
        name,
      );
      assert.deepEqual(state.submissions, [directoryTarget]);
    } finally {
      fixture.close();
    }
  }
});

/**
 * native 重试即使已 finished，遗留错误投影仍须阻止验收成功；断言稳定业务原因、单次命令数与共享总 deadline。
 * @author kongweiguang
 */
test("rejects directory rejection or retry that reopens a page, misroutes, or loses recovery controls", async () => {
  const expectedErrors = {
    reopen: /native 调用次数错误：ja_preview_open/u,
    duplicate: /native 调用次数错误：ja_preview_navigate_file/u,
    misroute: /native 调用次数错误：ja_preview_navigate_file/u,
    draft: /Preview 目录拒绝丢失了地址草稿/u,
    readonly: /Preview 目录拒绝后地址不可聚焦修改/u,
    "retry-reopen": /native 调用次数错误：ja_preview_open/u,
    "retry-error": /Preview 重试已完成但仍显示目录拒绝错误/u,
  };
  for (const failure of [
    "reopen",
    "duplicate",
    "misroute",
    "draft",
    "readonly",
    "retry-reopen",
    "retry-error",
  ]) {
    const fixture = previewDirectoryRejectionFixture();
    const { dom, input, page, workbench, directoryTarget, previewFixture, state } = fixture;
    /** 故障只改变 UI/native seam 的实际调用或可见数据，不替换被测 helper 内部判断。 */
    state.onDirectoryRejected = async () => {
      const identity = { sessionId: state.snapshot.id, generation: state.snapshot.generation };
      if (failure === "reopen")
        await dom.window.__TAURI_INTERNALS__.invoke("ja_preview_open", { input: identity });
      if (failure === "duplicate")
        await dom.window.__TAURI_INTERNALS__
          .invoke("ja_preview_navigate_file", { input: { ...identity, target: directoryTarget } })
          .catch(() => {});
      if (failure === "draft") input.value = previewFixture.url;
      if (failure === "readonly") input.readOnly = true;
    };
    if (failure === "misroute") state.fileCommand = "ja_preview_navigate";
    if (failure === "retry-error") state.keepError = true;
    /** 重开只在第二次显式提交发生，让回归必须走到恢复阶段才能发现错误。 */
    if (failure === "retry-reopen")
      state.onRetry = () =>
        dom.window.__TAURI_INTERNALS__.invoke("ja_preview_open", {
          input: { sessionId: state.snapshot.id, generation: state.snapshot.generation },
        });
    try {
      const deadline = Date.now() + 2_000;
      await assert.rejects(
        exercisePreviewDirectoryRejection(
          page,
          workbench,
          directoryTarget,
          previewFixture,
          deadline,
        ),
        expectedErrors[failure],
        failure,
      );
      assert.equal(state.submissions.length, failure.startsWith("retry-") ? 2 : 1, failure);
      if (failure === "retry-error") {
        assert.equal(state.snapshot.generation, 8);
        assert.equal(state.snapshot.load_status, "finished");
        assert.equal(state.snapshot.url, previewFixture.url + "after-directory-error");
        assert.ok(dom.window.document.querySelector('[role="alert"]'));
        assert.deepEqual(state.commands, [
          {
            command: "ja_preview_navigate_file",
            input: { sessionId: "preview-native-one", generation: 7, target: directoryTarget },
          },
          {
            command: "ja_preview_navigate",
            input: {
              sessionId: "preview-native-one",
              generation: 7,
              url: previewFixture.url + "after-directory-error",
              source: "user",
            },
          },
        ]);
        assert.equal(state.requests, 1);
        assert.ok(
          Date.now() <= deadline + 250,
          `shared Preview deadline exceeded by ${Date.now() - deadline}ms`,
        );
      }
    } finally {
      fixture.close();
    }
  }
});

/**
 * 旧错误投影必须跨过一轮完整的权威读取，清除时序用 JSDOM 事件驱动而不替 driver 代判。
 * @author kongweiguang
 */
test("waits for the Preview error projection to clear after a finished retry", async () => {
  const fixture = previewDirectoryRejectionFixture();
  const { dom, page, workbench, directoryTarget, previewFixture, state } = fixture;
  const retryUrl = previewFixture.url + "after-directory-error";
  const retryObserved = Promise.withResolvers();
  const releaseRetryRead = Promise.withResolvers();
  let retryReads = 0;
  let settled = false;
  state.keepError = true;
  /** 第二次 retry 读取说明第一次 afterRead 已因旧错误返回 pending；暂停 IPC 供测试推进真实 DOM 事件。 */
  state.onState = async () => {
    if (state.snapshot.url !== retryUrl) return;
    retryReads += 1;
    if (retryReads === 2) {
      retryObserved.resolve();
      await releaseRetryRead.promise;
    }
  };
  const deadline = Date.now() + 2_000;
  const pending = exercisePreviewDirectoryRejection(
    page,
    workbench,
    directoryTarget,
    previewFixture,
    deadline,
  );
  const outcome = pending.then(
    (value) => {
      settled = true;
      return { status: "resolved", value };
    },
    (error) => {
      settled = true;
      return { status: "rejected", error };
    },
  );
  try {
    const observation = await Promise.race([
      retryObserved.promise.then(() => "second-read"),
      outcome.then(() => "settled"),
    ]);
    assert.equal(observation, "second-read");
    assert.equal(retryReads, 2);
    assert.equal(state.snapshot.generation, 8);
    assert.equal(state.snapshot.load_status, "finished");
    assert.equal(state.snapshot.url, retryUrl);
    const staleAlert = dom.window.document.querySelector('[role="alert"]');
    assert.ok(staleAlert);
    assert.equal(settled, false);

    staleAlert.addEventListener("preview-projection", () => staleAlert.remove(), { once: true });
    staleAlert.dispatchEvent(new dom.window.Event("preview-projection"));
    releaseRetryRead.resolve();

    const result = await outcome;
    assert.equal(result.status, "resolved", result.error?.message);
    assert.equal(dom.window.document.querySelector('[role="alert"]'), null);
    assert.deepEqual(state.submissions, [directoryTarget, retryUrl]);
  } finally {
    releaseRetryRead.resolve();
    await outcome;
    fixture.close();
  }
});

/** 固定 RPC ID/generation 与 endpoint、完整进程身份的绑定语义，防止 Ready 或 Prime 预热替代 owner 证明。
 * @author kongweiguang
 */
test("freezes Runtime identity only after Ready and revalidates the same endpoint owner", async () => {
  const fixture = runtimeIsolationFixture();
  const owner = runtimeSidecarProcess(fixture);
  const endpoint = runtimeEndpointProof(fixture, owner).endpoint;
  const runtimeIdentity = {
    status: "ready",
    serverInstanceId: endpoint.serverInstanceId,
    generation: endpoint.runtimeGeneration,
  };
  const binding = {
    runtimeIdentity,
    endpoint,
    ownerIdentity: owner,
  };

  assert.deepEqual(assertRuntimeReadyBinding(undefined, runtimeIdentity, endpoint, owner), {
    serverInstanceId: endpoint.serverInstanceId,
    generation: endpoint.runtimeGeneration,
    ownerPid: owner.pid,
  });
  assert.doesNotThrow(() => assertRuntimeReadyBinding(binding, runtimeIdentity, endpoint, owner));
  const newServerSameGeneration = {
    ...runtimeIdentity,
    serverInstanceId: "srv_other",
  };
  assert.equal(newServerSameGeneration.generation, runtimeIdentity.generation);
  assert.throws(
    () => assertRuntimeReadyBinding(binding, newServerSameGeneration, endpoint, owner),
    /native_runtime_endpoint_identity_mismatch/u,
  );
  assert.throws(
    () =>
      assertRuntimeReadyBinding(
        binding,
        { ...runtimeIdentity, generation: runtimeIdentity.generation + 1 },
        endpoint,
        owner,
      ),
    /native_runtime_endpoint_identity_mismatch/u,
  );
  assert.throws(
    () =>
      assertRuntimeReadyBinding(
        binding,
        { ...runtimeIdentity, status: "starting" },
        endpoint,
        owner,
      ),
    /native_runtime_identity_incomplete/u,
  );
  assert.throws(
    () =>
      assertRuntimeReadyBinding(
        binding,
        runtimeIdentity,
        { ...endpoint, port: endpoint.port + 1 },
        owner,
      ),
    /native_runtime_binding_changed/u,
  );
  assert.throws(
    () =>
      assertRuntimeReadyBinding(binding, runtimeIdentity, endpoint, {
        ...owner,
        creationDate: "2026-09-29T01:01:00.000Z",
      }),
    /native_runtime_binding_changed/u,
  );

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const readyIndex = source.indexOf("const runtimeIdentity = await raceWithSignal(");
  const firstBranchIndex = source.indexOf('if (phase === "first") {', readyIndex);
  assert.ok(readyIndex >= 0 && firstBranchIndex > readyIndex);
  assert.match(
    source,
    /const freezeDirectories = bindRunLevel \? directories : \{ \.\.\.directories \}/u,
  );
  assert.match(
    source,
    /const bindRunLevel =\s*phase !== "profilePrime" && directories\.sharedRuntimeFreeze !== undefined/u,
  );
  assert.match(
    source,
    /if \(bindRunLevel\) \{\s*Object\.defineProperty\(directories, "sharedRuntimeFreeze"/u,
  );
  assert.match(
    source,
    /recordRuntimeIdentityHistory\(directories, freeze\.identities\.values\(\)\)/u,
  );
  assert.match(source, /directories\.sharedRuntimeIdentityHistory/u);
  assert.match(source, /handoffRuntimeFreezeAfterRestart\(/u);
  assert.match(source, /scope: "independent_host_phase"/u);
  assert.match(source, /const sameRunOwner =/u);
  assert.match(source, /runtime_handoff_epoch_already_transferred/u);
  assert.match(
    source,
    /const sharedRuntimeFreeze = directories\.sharedRuntimeFreeze;\s*if \(sharedRuntimeFreeze === undefined\)/u,
  );
});

/** 双 phase first 必须保留同一 frozen daemon；最终 phase 和单 phase first 才负责自然关闭。
 * @author kongweiguang
 */
test("keeps one App Server across phases and shuts it down only at the run boundary", async () => {
  assert.equal(isFinalRuntimeShutdownPhase("first", false), false);
  assert.equal(isFinalRuntimeShutdownPhase("second", false), true);
  assert.equal(isFinalRuntimeShutdownPhase("first", true), true);

  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n\nconst directExecution", mainStart);
  assert.ok(mainStart >= 0 && mainEnd > mainStart);
  const main = source.slice(mainStart, mainEnd);
  assert.match(main, /const singlePhaseAcceptanceMode = \[/u);
  assert.match(main, /if \(singlePhaseAcceptanceMode && phase === "second"\) break/u);
  assert.match(main, /cleanupMode === "owned_tree" && finalRuntimeShutdownPhase/u);
  assert.match(
    main,
    /cleanupMode === "owned_tree" &&\s*finalRuntimeShutdownPhase &&\s*directories\.runtimeShutdownAttempted !== true/u,
  );
  assert.match(
    main,
    /if \(directories\.runtime !== undefined && directories\.runtimeShutdownAttempted !== true\)\s*\{\s*try \{\s*await shutdownRunScopedRuntime/u,
  );
});

/** Native Host 的 JA_HOME 必须与隔离 USERPROFILE 下创建的 fixture 指向同一临时 .ja。
 * @author kongweiguang
 */
test("binds JA_HOME to the current E2E profile instead of inheriting the user's home", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("function buildTauriEnv(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const environment = source.slice(start, end);

  assert.match(environment, /USERPROFILE: directories\.settings/u);
  assert.match(environment, /JA_HOME: directories\.home/u);
  assert.ok(
    environment.indexOf("...rootProcessEnv") < environment.indexOf("JA_HOME: directories.home"),
  );
});

/** 隐藏 CDP overlay 只覆盖四个明确的无原生前台门禁，避免扩散到需要真实窗口输入的场景。
 * @author kongweiguang
 */
test("limits hidden CDP overlay to approved functional surfaces", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function writeE2eTauriConfig(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const writer = source.slice(start, end);

  assert.match(writer, /JA_E2E_HIDDEN_WINDOW_ONLY === "1"/u);
  assert.match(writer, /composerContextAcceptanceMode/u);
  assert.match(writer, /taskThreadsAcceptanceMode/u);
  assert.match(writer, /themeMatrixAcceptanceMode/u);
  assert.match(
    writer,
    /interactionPlanAcceptanceMode && configuredInteractionPlanSoakMinutes === 0/u,
  );
  assert.match(writer, /\.\.\.productionWindow/u);
  assert.match(writer, /visible: false, focus: false/u);
});

/** 隐藏窗口首次绑定只放宽 IsWindowVisible；仍要求唯一顶层 HWND、owner PID、精确 title 与有效客户区。
 * @author kongweiguang
 */
test("keeps the exact HWND identity checks when resolving a hidden window", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function resolveOwnedJaWindowHandle(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const resolver = source.slice(start, end);

  assert.match(
    resolver,
    /const allowHiddenWindow = process\.env\.JA_E2E_HIDDEN_WINDOW_ONLY === "1"/u,
  );
  assert.match(
    resolver,
    /\(!allowHidden&&!IsWindowVisible\(h\)\).*IsIconic\(h\).*GetWindow\(h,4\).*GetAncestor\(h,2\)!=h.*actualPid!=expectedPid\|\|!String.Equals\(Title\(h\),expectedTitle/u,
  );
  assert.match(resolver, /Find\(uint expectedPid,string expectedTitle,bool allowHidden\)/u);
  assert.match(resolver, /Matches\(\$handle,\[uint32\]\$pidValue,\$expectedTitle,\$allowHidden\)/u);
  assert.match(resolver, /observation\.visible !== !allowHiddenWindow/u);
});

/** 每次原生动作都要重新核对同一 HWND 与 owner；隐藏分支不得把可见性放宽以外的身份条件一并移除。
 * @author kongweiguang
 */
test("revalidates the same hidden HWND and owner before and after native actions", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function invokeOwnedWindowAction(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const action = source.slice(start, end);

  assert.match(
    action,
    /\(!allowHidden && !IsWindowVisible\(hWnd\)\).*GetWindow\(hWnd, 4\).*GetAncestor\(hWnd, 2\).*actualPid == expectedPid.*Title\(hWnd\), expectedTitle/u,
  );
  assert.equal(
    (
      action.match(
        /MatchesBound\(\$handle, \[uint32\]\$pidValue, \$expectedTitle, \$allowHidden\)/gu,
      ) ?? []
    ).length,
    2,
  );
  assert.match(action, /identityAfterCreationMatches/u);
  assert.match(action, /identityAfterCommandMatches/u);
});

/** Hidden native actions are read/resize only; denying focus/minimize/maximize here prevents future callers reopening the desktop.
 * @author kongweiguang
 */
test("rejects hidden-window focus, minimize, and maximize actions", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function invokeOwnedWindowAction(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const action = source.slice(start, end);
  const restriction = action.indexOf("$action -notin @('inspect', 'resize')");

  assert.ok(restriction >= 0);
  assert.ok(restriction < action.indexOf("$action -eq 'maximize'"));
  assert.ok(restriction < action.indexOf("$action -eq 'minimize'"));
  assert.ok(restriction < action.indexOf("$action -eq 'focus'"));
});

/** Hidden resize must still measure the real renderer/Win32 geometry while using only SWP_NOACTIVATE, never SWP_SHOWWINDOW.
 * @author kongweiguang
 */
test("resizes the actual hidden HWND without showing or activating it", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const actionStart = source.indexOf("async function invokeOwnedWindowAction(");
  const actionEnd = source.indexOf("\n}\n", actionStart);
  const resizeStart = source.indexOf("async function resizeOwnedNativeViewport(");
  const resizeEnd = source.indexOf("\n}\n", resizeStart);
  assert.ok(
    actionStart >= 0 &&
      actionEnd > actionStart &&
      resizeStart > actionEnd &&
      resizeEnd > resizeStart,
  );
  const action = source.slice(actionStart, actionEnd);
  const resize = source.slice(resizeStart, resizeEnd);

  assert.match(
    action,
    /if \(-not \$allowHidden\) \{ \[void\]\[JaE2eUser32\]::ShowWindowAsync\(\$handle, 9\); Start-Sleep -Milliseconds 100 \}/u,
  );
  assert.match(action, /\$positionFlags = if \(\$allowHidden\) \{ 0x0010 \} else \{ 0x0040 \}/u);
  assert.match(action, /SetWindowPos\([^\n]+\$positionFlags\)/u);
  assert.match(resize, /invokeOwnedWindowAction\(identity, "resize"/u);
  assert.match(resize, /readRendererWindowMetrics\(page\)/u);
  assert.match(
    resize,
    /renderer\.innerWidth === viewport\.width && renderer\.innerHeight === viewport\.height/u,
  );
});

/** @author kongweiguang 确保鼠标验收点击可见选项行，同时保留真实选中、恢复与pending断言。 */
test("uses the visible Interaction option row as the pointer target", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);

  assert.ok(
    interaction.includes(
      `skipCard.locator('[data-option-id="option_ui"]').click({ timeout: timeout() });`,
    ),
  );
  assert.doesNotMatch(
    interaction,
    /skipCard\.locator\('\[data-option-id="option_ui"\] input'\)\.check\(/u,
  );
  assert.ok(interaction.includes("interactionFeedback.readback?.checked !== true"));
  assert.ok(interaction.includes('answer.optionIds.includes("option_ui")'));
  assert.ok(interaction.includes('stage("answers_restored_pending")'));
  assert.ok(interaction.includes('snapshot.value.request.status === "pending"'));
});

/** @author kongweiguang 暂停失败只记录已发送的标量边界，不复制会话身份、参数或错误正文。 */
test("projects Plan pause failure without request identity or error bodies", async () => {
  const { projectInteractionPlanPauseFailure } = await import("./windows-desktop-smoke.mjs");
  assert.deepEqual(
    projectInteractionPlanPauseFailure({
      expectedPlanRevision: 3,
      beforeStatus: "executing",
      sameRunIdentity: true,
      errorCode: "CONFLICT",
      durationMs: 17.5,
      planId: "private-plan-sentinel",
      runId: "private-run-sentinel",
      message: "private-error-sentinel",
    }),
    {
      expectedPlanRevision: 3,
      beforeStatus: "executing",
      sameRunIdentity: true,
      errorCode: "CONFLICT",
      durationMs: 17.5,
    },
  );
  assert.deepEqual(
    projectInteractionPlanPauseFailure({
      expectedPlanRevision: Number.MAX_SAFE_INTEGER + 1,
      beforeStatus: "private-status",
      sameRunIdentity: "private-run",
      errorCode: "private-error",
      durationMs: Number.POSITIVE_INFINITY,
    }),
    {
      expectedPlanRevision: null,
      beforeStatus: "unknown",
      sameRunIdentity: null,
      errorCode: "unknown",
      durationMs: null,
    },
  );
});

/** @author kongweiguang 诊断只在失败结果返回后写入；不得延迟暂停、补发控制或改变原来的成功断言。 */
test("writes Plan pause failure diagnostics only after the failed control response", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  const interaction = source.slice(start, end);
  const pauseCall = interaction.indexOf(
    'const pauseResult = await invoke("ja_runtime_plan_pause",',
  );
  const failureBranch = interaction.indexOf("if (!pauseSettledBeforeVisible) {", pauseCall);
  const diagnostic = interaction.indexOf('"plan-pause-control-failure.json"', failureBranch);
  const originalFailure = interaction.indexOf("Interaction Plan 暂停未安全结算", diagnostic);
  assert.ok(start >= 0 && end > start && pauseCall >= 0);
  assert.ok(
    failureBranch > pauseCall && diagnostic > failureBranch && originalFailure > diagnostic,
  );
  assert.equal(interaction.match(/invoke\("ja_runtime_plan_pause"/gu)?.length, 1);
  assert.ok(
    interaction.includes('pauseResult.ok === true && pauseResult.value?.plan?.status === "paused"'),
  );
  assert.doesNotMatch(
    interaction.slice(pauseCall, failureBranch),
    /await writeFile|setTimeout|sleep\(/u,
  );
});

/** @author kongweiguang 恢复必须由成功的权威 paused 快照、原 Run 与可发送 revision 共同准入。 */
test("rejects Interaction Plan resume when its authority read or identity is invalid", async () => {
  const { projectInteractionPlanResumeAdmission } = await import("./windows-desktop-smoke.mjs");
  assert.equal(typeof projectInteractionPlanResumeAdmission, "function");

  const accepted = projectInteractionPlanResumeAdmission(
    { ok: true, value: { plan: { status: "paused", revision: 12, activeRunId: "run-current" } } },
    "run-current",
  );
  assert.deepEqual(accepted, {
    allowed: true,
    rejectionReason: null,
    beforeResumeStatus: "paused",
    beforeResumeRevision: 12,
    sameRunIdentity: true,
    expectedPlanRevision: 12,
  });

  const rejected = [
    [{ ok: false, code: "PLAN_NOT_FOUND", message: "private-read-body" }, "PLAN_READ_FAILED"],
    [
      {
        ok: true,
        value: { plan: { status: "executing", revision: 13, activeRunId: "run-current" } },
      },
      "PLAN_NOT_PAUSED",
    ],
    [
      { ok: true, value: { plan: { status: "paused", revision: 14, activeRunId: "run-other" } } },
      "RUN_IDENTITY_MISMATCH",
    ],
    [
      {
        ok: true,
        value: { plan: { status: "paused", revision: Number.NaN, activeRunId: "run-current" } },
      },
      "INVALID_REVISION",
    ],
    [
      { ok: true, value: { plan: { status: "paused", revision: -1, activeRunId: "run-current" } } },
      "INVALID_REVISION",
    ],
  ];
  for (const [snapshot, rejectionReason] of rejected) {
    const admission = projectInteractionPlanResumeAdmission(snapshot, "run-current");
    assert.equal(admission.allowed, false);
    assert.equal(admission.rejectionReason, rejectionReason);
    assert.equal(admission.expectedPlanRevision, null);
  }
});

/**
 * 暂停视觉证据必须按响应归属的精确 Turn 校验，不能把 continuation 源行或同线程其它 Turn 混为一谈。
 * @author kongweiguang
 */
test("projects the paused Interaction Turn without banning another running response", async () => {
  const { projectInteractionPlanPausedTurnVisualEvidence } = await import(
    "./windows-desktop-smoke.mjs"
  );
  assert.equal(typeof projectInteractionPlanPausedTurnVisualEvidence, "function");

  const pausedContinuationRow = {
    rowTurnId: "turn-source",
    responseTurnId: "turn-claimed",
    responseState: "suspended",
    responseStatusText: "已暂停",
    hasWorkingDots: false,
    workProcessState: "suspended",
  };
  const otherRunningRow = {
    rowTurnId: "turn-other",
    responseTurnId: "turn-other",
    responseState: "streaming",
    responseStatusText: "正在工作",
    hasWorkingDots: true,
    workProcessState: "running",
  };

  assert.deepEqual(
    projectInteractionPlanPausedTurnVisualEvidence(
      [pausedContinuationRow, otherRunningRow],
      "turn-claimed",
    ),
    { kind: "paused" },
  );
  assert.deepEqual(
    projectInteractionPlanPausedTurnVisualEvidence(
      [{ ...pausedContinuationRow, responseTurnId: "turn-continuation" }],
      "turn-claimed",
    ),
    { kind: "waiting" },
  );
  assert.deepEqual(
    projectInteractionPlanPausedTurnVisualEvidence(
      [{ ...pausedContinuationRow, responseState: "streaming", hasWorkingDots: true }],
      "turn-claimed",
    ),
    { kind: "waiting" },
  );
  assert.deepEqual(
    projectInteractionPlanPausedTurnVisualEvidence(
      [pausedContinuationRow, pausedContinuationRow],
      "turn-claimed",
    ),
    { kind: "ambiguous" },
  );
  assert.deepEqual(projectInteractionPlanPausedTurnVisualEvidence([], null), {
    kind: "between_turns",
  });
  assert.deepEqual(projectInteractionPlanPausedTurnVisualEvidence([], ""), {
    kind: "invalid_claim",
  });
});

/**
 * 暂停账本确认后才读取 Turn 并等待对应响应行，避免改变前面的 approve/pause 竞争窗口或恢复准入。
 * @author kongweiguang
 */
test("checks only the durable claimed Turn projection after pause and before resume", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);
  const pauseAck = interaction.indexOf("const pauseSettledBeforeVisible =");
  const pausedLedger = interaction.indexOf(
    "const pausedLedger = planExecutionLedgerFact(",
    pauseAck,
  );
  const authorityRead = interaction.indexOf(
    "await readPlanThreadAuthority(page, threadId)",
    pausedLedger,
  );
  const visualWait = interaction.indexOf('"Interaction Plan 暂停 Turn 时间线投影"', authorityRead);
  const beforeResumeRead = interaction.indexOf(
    'const beforeResume = await invoke("ja_runtime_plan_read",',
  );
  assert.ok(pauseAck >= 0 && pausedLedger > pauseAck);
  assert.ok(authorityRead > pausedLedger && visualWait > authorityRead);
  assert.ok(beforeResumeRead > visualWait);
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /state === "CLAIMED"/u);
  assert.match(
    interaction.slice(pausedLedger, beforeResumeRead),
    /claimedTurn\?\.status !== "suspended"/u,
  );
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /data-response-turn-id/u);
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /data-response-state/u);
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /已暂停/u);
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /ja-chat-activity-dots/u);
  assert.match(interaction.slice(pausedLedger, beforeResumeRead), /ja-work-process/u);
});

/** @author kongweiguang 失败证据只保留控制边界标量，禁止持久化 Plan/Run 身份或错误正文。 */
test("projects failed Interaction Plan resume evidence with closed and redacted fields", async () => {
  const { projectInteractionPlanResumeFailure } = await import("./windows-desktop-smoke.mjs");
  assert.equal(typeof projectInteractionPlanResumeFailure, "function");
  const projected = projectInteractionPlanResumeFailure({
    pauseResponseStatus: "paused",
    pauseResponseRevision: 8,
    pauseResponseSameRunIdentity: true,
    pausedLedgerStatus: "PAUSED",
    pausedLedgerConfirmed: true,
    pausedLedgerSameRunIdentity: true,
    beforeResumeReadSucceeded: true,
    beforeResumeReadErrorCode: null,
    beforeResumeStatus: "paused",
    beforeResumeRevision: 9,
    beforeResumeSameRunIdentity: true,
    rejectionReason: null,
    resumeAttempted: true,
    sentExpectedPlanRevision: 9,
    resumeResponseStatus: "unknown-state",
    resumeResponseRevision: Number.MAX_SAFE_INTEGER + 1,
    resumeResponseSameRunIdentity: "run-private-id",
    resumeErrorCode: "PLAN_INVALID_STATE",
    durationMs: 12.5,
    planId: "plan-private-sentinel",
    runId: "run-private-sentinel",
    operationKey: "operation-private-sentinel",
    message: "private-error-body-sentinel",
    request: { expectedPlanRevision: 9, body: "private-payload-sentinel" },
  });
  assert.deepEqual(projected, {
    pauseResponseStatus: "paused",
    pauseResponseRevision: 8,
    pauseResponseSameRunIdentity: true,
    pausedLedgerStatus: "PAUSED",
    pausedLedgerConfirmed: true,
    pausedLedgerSameRunIdentity: true,
    beforeResumeReadSucceeded: true,
    beforeResumeReadErrorCode: null,
    beforeResumeStatus: "paused",
    beforeResumeRevision: 9,
    beforeResumeSameRunIdentity: true,
    rejectionReason: null,
    resumeAttempted: true,
    sentExpectedPlanRevision: 9,
    resumeResponseStatus: "unknown",
    resumeResponseRevision: null,
    resumeResponseSameRunIdentity: null,
    resumeErrorCode: "PLAN_INVALID_STATE",
    durationMs: 12.5,
  });
  const serialized = JSON.stringify(projected);
  for (const sentinel of ["private", "sentinel", "operationKey", "planId", "runId", "payload"]) {
    assert.equal(serialized.includes(sentinel), false);
  }

  assert.deepEqual(
    projectInteractionPlanResumeFailure({
      beforeResumeReadSucceeded: false,
      beforeResumeReadErrorCode: "private-error-sentinel",
      beforeResumeStatus: "private-status",
      beforeResumeRevision: Number.POSITIVE_INFINITY,
      beforeResumeSameRunIdentity: "private-run",
      rejectionReason: "private-reason",
      resumeAttempted: false,
      sentExpectedPlanRevision: 99,
      resumeErrorCode: "private-error-sentinel",
      durationMs: 10,
    }),
    {
      pauseResponseStatus: "unknown",
      pauseResponseRevision: null,
      pauseResponseSameRunIdentity: null,
      pausedLedgerStatus: "unknown",
      pausedLedgerConfirmed: false,
      pausedLedgerSameRunIdentity: null,
      beforeResumeReadSucceeded: false,
      beforeResumeReadErrorCode: "unknown",
      beforeResumeStatus: "unknown",
      beforeResumeRevision: null,
      beforeResumeSameRunIdentity: null,
      rejectionReason: "unknown",
      resumeAttempted: false,
      sentExpectedPlanRevision: null,
      resumeResponseStatus: null,
      resumeResponseRevision: null,
      resumeResponseSameRunIdentity: null,
      resumeErrorCode: null,
      durationMs: null,
    },
  );
});

/** @author kongweiguang 在发出 resume 前先锁定 authoritative paused snapshot，失败分支不得触碰控制 RPC。 */
test("checks Interaction Plan resume authority before sending and persists only failed control evidence", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);
  const beforeResumeRead = interaction.indexOf(
    'const beforeResume = await invoke("ja_runtime_plan_read",',
  );
  const admission = interaction.indexOf(
    "const resumeAdmission = projectInteractionPlanResumeAdmission(",
  );
  const rejected = interaction.indexOf("if (!resumeAdmission.allowed)", admission);
  const resumeInvoke = interaction.indexOf(
    'const resumeResult = await invoke("ja_runtime_plan_resume",',
  );
  const outcomeFailure = interaction.indexOf("if (!resumeUsedSameRun)", resumeInvoke);
  assert.ok(beforeResumeRead >= 0 && admission > beforeResumeRead);
  assert.ok(rejected > admission && resumeInvoke > rejected && outcomeFailure > resumeInvoke);
  assert.ok(interaction.slice(rejected, resumeInvoke).includes("persistResumeFailureDiagnostic"));
  assert.match(
    interaction.slice(
      resumeInvoke,
      interaction.indexOf("providerFixture.setPlanContext(", resumeInvoke),
    ),
    /expectedPlanRevision:\s*resumeAdmission\.expectedPlanRevision/u,
  );
  assert.doesNotMatch(
    interaction.slice(
      resumeInvoke,
      interaction.indexOf("providerFixture.setPlanContext(", resumeInvoke),
    ),
    /beforeResume\.value\?\.plan\?\.revision/u,
  );
  assert.ok(interaction.slice(outcomeFailure).includes("persistResumeFailureDiagnostic"));
  assert.match(
    interaction,
    /const pausedLedger = planExecutionLedgerFact\(directories, plan\.plan\.planId, runId\);\s*if \(pausedLedger\?\.run\?\.status !== "PAUSED"\)/u,
  );
});

/** @author kongweiguang 固定“取消提问”和“取消运行”是两个不同的用户动作与 Turn 终态。 */
test("closes a cancelled Interaction Turn through the Composer before the next prompt", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);

  const interactionCancelStage = interaction.indexOf(
    'stage("interaction_cancelled_suspended_turn")',
  );
  const composerCancelAction = interaction.indexOf(
    'page.getByRole("button", { name: "取消运行", exact: true })',
  );
  const composerCancelledStage = interaction.indexOf('stage("composer_cancelled_suspended_turn")');
  const nextPrompt = interaction.indexOf("const raceSnapshot = await requestInteractionBatch(");
  assert.ok(interactionCancelStage >= 0);
  assert.ok(composerCancelAction > interactionCancelStage);
  assert.ok(composerCancelledStage > composerCancelAction);
  assert.ok(nextPrompt > composerCancelledStage);
  assert.ok(interaction.includes('(turn) => turn.status === "waiting_input"'));
  assert.ok(
    interaction.includes(
      'turn.turnId === suspendedInteractionTurn.turnId && turn.status === "suspended"',
    ),
  );
  assert.ok(
    interaction.includes(
      'turn.turnId === suspendedInteractionTurn.turnId && turn.status === "cancelled"',
    ),
  );
  assert.ok(interaction.includes("await waitForComposerAdmission(page, deadline);"));
  assert.doesNotMatch(interaction, /turn\.status !== "running" && turn\.status !== "suspended"/u);
});

/** @author kongweiguang 固定 Plan 超时诊断只记录已完成边界，不插入探测或改变原动作顺序。 */
test("records Interaction Plan execution checkpoints around existing UI waits", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);
  const checkpoints = [
    'stage("execute_composer_admitted")',
    'stage("execute_plan_proposed")',
    'stage("execute_reload_runtime_ready")',
    'stage("execute_thread_select_started")',
    'stage("execute_thread_selected")',
    'stage("execute_plan_button_click_started")',
    'stage("execute_plan_button_clicked")',
    'stage("execute_plan_invoke_wait_started")',
    'stage("execute_plan_invoke_observed")',
  ];
  let previous = -1;
  for (const checkpoint of checkpoints) {
    const index = interaction.indexOf(checkpoint);
    assert.ok(index > previous, `missing or unordered checkpoint: ${checkpoint}`);
    assert.equal(interaction.indexOf(checkpoint, index + checkpoint.length), -1);
    previous = index;
  }

  const clickStart = interaction.indexOf(checkpoints[5]);
  const clicked = interaction.indexOf(checkpoints[6]);
  assert.match(
    interaction.slice(clickStart, clicked),
    /await planCard\.getByRole\("button", \{ name: "执行", exact: true \}\)\.click\(/u,
  );
  const invokeWait = interaction.indexOf(checkpoints[7]);
  const invokeObserved = interaction.indexOf(checkpoints[8]);
  assert.match(
    interaction.slice(invokeWait, invokeObserved),
    /const executeInput = await waitForCondition\(\s*"real Plan execute input"/u,
  );
  assert.doesNotMatch(interaction, /await stage\(/u);
});

/** @author kongweiguang 缺少 invoke 终态时必须保留 null/unknown，不能伪报失败或同 Run 结论。 */
test("keeps missing Interaction Plan execute observations unknown", () => {
  assert.deepEqual(projectInteractionPlanExecuteAttempt(null), {
    firstUiInvoke: { completed: null, errorCode: null },
    admissionSnapshotStatus: "unknown",
    admissionSnapshotErrorCode: null,
    duplicateAttempted: false,
    duplicateReplay: { completed: null, errorCode: null },
    sameRunIdentity: null,
  });
  assert.deepEqual(
    projectInteractionPlanExecuteAttempt({
      firstUiInvoke: { phase: "start", errorCode: "DO_NOT_PROJECT" },
      duplicateAttempted: true,
      duplicateReplay: null,
    }),
    {
      firstUiInvoke: { completed: null, errorCode: null },
      admissionSnapshotStatus: "unknown",
      admissionSnapshotErrorCode: null,
      duplicateAttempted: true,
      duplicateReplay: { completed: null, errorCode: null },
      sameRunIdentity: null,
    },
  );
});

/** @author kongweiguang 成功闭环只投影 ACK、准入状态和同 Run 布尔值，不输出 Run identity。 */
test("projects successful Interaction Plan execute and idempotent replay safely", () => {
  assert.deepEqual(
    projectInteractionPlanExecuteAttempt({
      firstUiInvoke: { phase: "resolved" },
      admissionSnapshotStatus: "executing",
      duplicateAttempted: true,
      duplicateReplay: { phase: "resolved" },
      sameRunIdentity: true,
    }),
    {
      firstUiInvoke: { completed: true, errorCode: null },
      admissionSnapshotStatus: "executing",
      admissionSnapshotErrorCode: null,
      duplicateAttempted: true,
      duplicateReplay: { completed: true, errorCode: null },
      sameRunIdentity: true,
    },
  );
});

/** @author kongweiguang 对失败码和 Plan status 使用当前协议闭集，未知值不得泄漏到 E2E summary。 */
test("projects rejected Interaction Plan calls with closed status and error-code sets", () => {
  assert.deepEqual(
    projectInteractionPlanExecuteAttempt({
      firstUiInvoke: { phase: "rejected", errorCode: "INVALID_PARAMS" },
      admissionSnapshotStatus: "not_a_plan_status",
      admissionSnapshotErrorCode: "private-error-text",
      duplicateAttempted: true,
      duplicateReplay: { phase: "rejected", errorCode: "PLAN_INVALID_STATE" },
      sameRunIdentity: false,
    }),
    {
      firstUiInvoke: { completed: false, errorCode: "INVALID_PARAMS" },
      admissionSnapshotStatus: "unknown",
      admissionSnapshotErrorCode: "unknown",
      duplicateAttempted: true,
      duplicateReplay: { completed: false, errorCode: "PLAN_INVALID_STATE" },
      sameRunIdentity: false,
    },
  );
});

/** @author kongweiguang 即使输入对象混入敏感哨兵，报告投影也只返回允许的标量。 */
test("never serializes Interaction Plan request identity or result payloads into diagnostics", () => {
  const projected = projectInteractionPlanExecuteAttempt({
    firstUiInvoke: { phase: "rejected", errorCode: "C:/private/token-sentinel" },
    admissionSnapshotStatus: "private-thread-sentinel",
    admissionSnapshotErrorCode: "private-error-sentinel",
    duplicateAttempted: true,
    duplicateReplay: { phase: "resolved", result: "private-body-sentinel" },
    sameRunIdentity: true,
    threadId: "thr-private-id-sentinel",
    planId: "plan-private-id-sentinel",
    operationKey: "private-operation-sentinel",
    activeRunId: "run-private-id-sentinel",
  });
  const serialized = JSON.stringify(projected);
  for (const sentinel of [
    "private",
    "sentinel",
    "threadId",
    "planId",
    "operationKey",
    "activeRunId",
  ]) {
    assert.equal(serialized.includes(sentinel), false);
  }
  assert.equal(projected.firstUiInvoke.errorCode, "unknown");
  assert.equal(projected.admissionSnapshotStatus, "unknown");
});

/** @author kongweiguang 保证阶段证据先写 outer failure summary，再执行可能失败的原始门禁断言。 */
test("persists Interaction Plan attempt projection before admission and replay assertions", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  const start = source.indexOf("async function runIndependentInteractionPlanAcceptanceSession(");
  const end = source.indexOf("\n}\n", start);
  assert.ok(start >= 0 && end > start);
  const interaction = source.slice(start, end);
  const firstObserved = interaction.indexOf('stage("execute_plan_invoke_observed")');
  const firstPersist = interaction.indexOf("publishExecuteAttempt();", firstObserved);
  const admissionPoll = interaction.indexOf("const admissionSnapshot = await waitForCondition(");
  const duplicateMarked = interaction.indexOf("executeAttempt.duplicateAttempted = true;");
  const duplicateBefore = interaction.indexOf("publishExecuteAttempt();", duplicateMarked);
  const duplicateRequest = interaction.indexOf(
    'duplicateExecute = await invoke("ja_runtime_plan_execute", executeInput)',
    duplicateBefore,
  );
  const duplicateProjection = interaction.indexOf(
    "executeAttempt.duplicateReplay = {",
    duplicateRequest,
  );
  const duplicatePersist = interaction.indexOf("publishExecuteAttempt();", duplicateProjection);
  const finalAssertion = interaction.indexOf("if (!atomicExecute)", duplicatePersist);
  assert.ok(firstObserved >= 0 && firstPersist > firstObserved && admissionPoll > firstPersist);
  assert.ok(
    duplicateMarked >= 0 && duplicateBefore > duplicateMarked && duplicateRequest > duplicateBefore,
  );
  assert.ok(duplicateProjection > duplicateRequest && duplicatePersist > duplicateProjection);
  assert.ok(finalAssertion > duplicatePersist);

  const mainStart = source.indexOf("async function main()");
  const mainEnd = source.indexOf("\n}\n", mainStart);
  const main = source.slice(mainStart, mainEnd);
  assert.ok(main.includes("interactionPlanExecuteAttempt: attempt"));
  assert.ok(main.includes("recordExecuteAttempt,\n                  ),"));
  const summaryWriter = source.slice(source.indexOf("async function writeRunSummary("));
  assert.ok(summaryWriter.includes("...evidence,"));
  assert.doesNotMatch(interaction, /const firstExecute\s*=|firstExecute\.value/u);
});
