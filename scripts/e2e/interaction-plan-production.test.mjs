// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  INTERACTION_PLAN_CONTRACT_VERSION,
  INTERACTION_PLAN_UI_CONTRACT,
  buildInteractionPlanDesktopEnvironment,
  collectInteractionPlanAcceptanceReport,
  findMissingInteractionPlanHooks,
  markInteractionPlanFunctionalReport,
  parseInteractionPlanArguments,
  readStagedInteractionPlanSidecar,
  validateInteractionPlanAcceptanceReport,
} from "./interaction-plan-production.mjs";
import {
  InteractionPlanFixtureError,
  createInteractionPlanStateFixture,
} from "./fixtures/interaction-plan-state-fixture.mjs";
import {
  interactionPlanProviderScenario,
  interactionPlanTextStream,
  interactionPlanToolStream,
} from "./fixtures/interaction-plan-provider-scenario.mjs";
import {
  computePaintedRect,
  computeVisibleControlOverlaps,
} from "./interaction-plan-webview2-driver.mjs";

/** 构造覆盖所有真窗阶段的最小合法报告，保留浏览器反馈的真实测量字段供合同回归使用。 */
function validReport() {
  const visualMatrix = [];
  for (const [index, width] of INTERACTION_PLAN_UI_CONTRACT.widths.entries()) {
    for (const [zoomIndex, zoom] of INTERACTION_PLAN_UI_CONTRACT.zooms.entries()) {
      const theme =
        INTERACTION_PLAN_UI_CONTRACT.themes[
          (index * INTERACTION_PLAN_UI_CONTRACT.zooms.length + zoomIndex) %
            INTERACTION_PLAN_UI_CONTRACT.themes.length
        ];
      const commonFrame = {
        width,
        nativeWidth: width,
        nativeHeight: 900,
        cssViewportWidth: zoom === 100 ? width : Math.max(1, Math.floor((width * 100) / zoom)),
        zoom,
        zoomEvidence: "native_webview_zoom",
        theme,
        resolvedTheme: theme === "system" ? "dark" : theme,
        mediaReducedMotion: width === 799 && zoom === 100,
        reducedMotion: width === 799 && zoom === 100,
        reducedTransparency: width === 800 && zoom === 125,
        highContrast: width === 800 && zoom === 150,
        documentOverflow: 0,
        horizontalOverflow: 0,
        controlOverlapCount: 0,
        unnamedControlCount: 0,
        questionComposerOverlapCount: 0,
        questionComposerOverlaps: [],
        composerInViewport: true,
        devicePixelRatio: 1,
        pngWidth: width,
        pngHeight: 900,
        pngByteLength: 1024,
        pngDimensionsMatchNativeViewport: true,
        planControlCount: 0,
        planObjectiveVisible: false,
        planObjectiveRect: null,
        planActionVisible: false,
        planActionRect: null,
      };
      const questionAccessibilityEvidence = {
        version: 1,
        surface: "webview2_cdp",
        source: "Accessibility.getFullAXTree",
        fileName: `${width}-${zoom}-${theme}.accessibility.json`,
        screenReaderNarrationVerified: false,
        axNodeCount: 7,
        question: {
          label: { present: true, length: 8 },
          groupLabel: { present: true, length: 8 },
          groupRole: "radiogroup",
          required: true,
        },
        controls: [
          {
            role: "radio",
            accessibleName: { present: true, length: 4 },
            checked: true,
            required: true,
          },
          {
            role: "radio",
            accessibleName: { present: true, length: 4 },
            checked: false,
            required: true,
          },
        ],
        controlCount: 2,
        roleCounts: { radio: 2, checkbox: 0 },
      };
      visualMatrix.push({
        ...commonFrame,
        frameId: `${width}-${zoom}-${theme}-question`,
        surfaceMode: "question",
        surfacePhase: "question",
        longText: zoom === 200,
        questionTextLength: zoom === 200 ? 120 : 28,
        pendingQuestionCount: 1,
        collapsedQuestionCount: 0,
        questionCardCount: 1,
        planSurfaceCount: 0,
        planActionCount: 0,
        accessibilityEvidence: questionAccessibilityEvidence,
      });
      visualMatrix.push({
        ...commonFrame,
        frameId: `${width}-${zoom}-${theme}-plan-content`,
        surfaceMode: "plan",
        surfacePhase: "content",
        longText: false,
        questionTextLength: 0,
        pendingQuestionCount: 0,
        collapsedQuestionCount: 1,
        questionCardCount: 0,
        planSurfaceCount: 1,
        planControlCount: 3,
        planObjectiveVisible: true,
        planObjectiveRect: { left: 20, top: 96, right: 400, bottom: 128, width: 380, height: 32 },
        planActionVisible: false,
        planActionRect: null,
        planActionCount: 0,
        accessibilityEvidence: null,
      });
      visualMatrix.push({
        ...commonFrame,
        frameId: `${width}-${zoom}-${theme}-plan-action`,
        surfaceMode: "plan",
        surfacePhase: "action",
        longText: false,
        questionTextLength: 0,
        pendingQuestionCount: 0,
        collapsedQuestionCount: 1,
        questionCardCount: 0,
        planSurfaceCount: 1,
        planControlCount: 3,
        planObjectiveVisible: false,
        planObjectiveRect: null,
        planActionVisible: true,
        planActionRect: { left: 320, top: 390, right: 420, bottom: 426, width: 100, height: 36 },
        planActionCount: 1,
        accessibilityEvidence: null,
      });
    }
  }
  return {
    contractVersion: INTERACTION_PLAN_CONTRACT_VERSION,
    runtime: {
      surface: "tauri_webview2",
      nativeWindow: true,
      deterministicMockProvider: true,
      realProviderRequests: 0,
      nativeSidecar: { used: true, identityMatched: true, noFallback: true },
    },
    interaction: {
      singleChoice: true,
      multiChoice: true,
      customAnswer: true,
      explicitSkip: true,
      collapsePreservedDraft: true,
      restartRestoredPendingRequest: true,
      duplicateResponseIdempotent: true,
      staleResponseRejected: true,
      cancelDoesNotResumeTurn: true,
      raceSingleWinner: true,
      imeCompositionSafe: true,
      keyboardComplete: true,
      feedbackMeasuredInBrowser: true,
      feedbackEventType: "change",
      feedbackLatencyMs: 8.25,
      feedbackReadback: { checked: true, value: "on", optionId: "option_ui" },
      questionKinds: ["single", "multi", "custom", "skip"],
      pendingThreadCount: 1,
      answerSummaryCollapsed: true,
      noImplicitDefault: true,
    },
    readonly: {
      shellRejected: true,
      workspaceWriteRejected: true,
      externalWriteRejected: true,
      subagentWriteRejected: true,
      readOnlyToolsOnly: true,
      executionBoundaryRequired: true,
    },
    execution: {
      atomicExecute: true,
      completionProof: {
        completionGateVerified: true,
        completionEventCount: 1,
        turnCount: 2,
        notMetEvaluationCount: 1,
        metEvaluationCount: 1,
      },
      executionAuthorizationRecorded: true,
      singleRunCreated: true,
      continuationKeptBudget: true,
      pauseSettledBeforeVisible: true,
      resumeUsedSameRun: true,
      stopPreservedHistory: true,
      versionConflictDidNotExecuteLatest: true,
      goalNotImplicitlyCreated: true,
      runCount: 1,
      continuationTurnCount: 2,
      remainingBudgetNonNegative: true,
    },
    recovery: {
      restartRestoredSnapshot: true,
      unknownSideEffectPaused: true,
      unknownSideEffectNotReplayed: true,
      successfulToolNotReplayed: true,
      lateEventIgnored: true,
      resumeExplicitlyRequired: true,
      status: "needs_attention",
    },
    isolation: {
      goalWithoutPlan: true,
      planWithoutGoal: true,
      threadAQuestionNotVisibleInThreadB: true,
      threadBQuestionNotVisibleInThreadA: true,
      hiddenThreadDidNotMaterializePlan: true,
    },
    visualMatrix,
    goalRegression: {
      statuses: ["active", "paused", "active"],
      planLinkUnchanged: true,
      completionProof: {
        achieved: true,
        planLinkNull: true,
        goalId: "goal_only_fixture",
        goalDefinitionRevision: 1,
        runId: "run_goal_only_fixture",
        evidence: {
          goalId: "goal_only_fixture",
          goalDefinitionRevision: 1,
          runId: "run_goal_only_fixture",
          count: 1,
          criterionIds: ["criterion_goal_only"],
          sourceIds: ["call_goal_only_shell"],
        },
        evaluation: {
          goalId: "goal_only_fixture",
          goalDefinitionRevision: 1,
          runId: "run_goal_only_fixture",
          count: 1,
          metCount: 1,
          evaluationId: "evaluation_goal_only",
          verdict: "MET",
        },
        completionEvent: {
          goalId: "goal_only_fixture",
          goalDefinitionRevision: 1,
          runId: "run_goal_only_fixture",
          count: 1,
          eventId: "event_goal_only_achieved",
          eventSequence: 9,
        },
        pendingToolCount: 0,
        pendingInteractionCount: 0,
        completionGateVerified: true,
      },
    },
    soak: { requestedMinutes: 0, elapsedMs: 0, healthChecks: 0, healthy: true },
  };
}

function expectCode(action, code) {
  assert.throws(
    action,
    (error) => error instanceof InteractionPlanFixtureError && error.code === code,
  );
}

test("严格报告拒绝静态 surface、真实 Provider、缺失交互维度和缩短 soak", () => {
  const report = validReport();
  assert.equal(validateInteractionPlanAcceptanceReport(report, { expectedSoakMinutes: 0 }), report);
  assert.equal(report.visualMatrix[0].accessibilityEvidence.screenReaderNarrationVerified, false);
  assert.equal(report.verificationStatus, "VERIFIED");
  for (const mutate of [
    (copy) => (copy.runtime.surface = "source_contract"),
    (copy) => (copy.runtime.realProviderRequests = 1),
    (copy) => (copy.interaction.multiChoice = false),
    (copy) => (copy.readonly.shellRejected = false),
    (copy) => (copy.execution.runCount = 2),
    (copy) => (copy.recovery.unknownSideEffectNotReplayed = false),
    (copy) => (copy.interaction.feedbackLatencyMs = 100),
    (copy) => copy.visualMatrix.pop(),
    (copy) => {
      delete copy.visualMatrix[0].accessibilityEvidence;
    },
    (copy) => (copy.visualMatrix[0].accessibilityEvidence.screenReaderNarrationVerified = true),
    (copy) => (copy.visualMatrix[0].questionCardCount = 0),
    (copy) => (copy.visualMatrix[0].theme = "unknown"),
    (copy) => (copy.visualMatrix[0].zoom = 110),
  ]) {
    const copy = structuredClone(report);
    mutate(copy);
    if (
      copy.interaction.multiChoice === false ||
      copy.readonly.shellRejected === false ||
      copy.recovery.unknownSideEffectNotReplayed === false
    ) {
      assert.equal(
        validateInteractionPlanAcceptanceReport(copy, { expectedSoakMinutes: 0 })
          .verificationStatus,
        "NOT_VERIFIED",
      );
    } else {
      assert.throws(() =>
        validateInteractionPlanAcceptanceReport(copy, { expectedSoakMinutes: 0 }),
      );
    }
  }
  const shortened = structuredClone(report);
  shortened.soak.requestedMinutes = 120;
  shortened.soak.elapsedMs = 1;
  assert.throws(() => validateInteractionPlanAcceptanceReport(shortened));
});

test("driver 阶段按权威顺序采集，并拒绝缺失阶段", async () => {
  const expected = validReport();
  const calls = [];
  const driver = {};
  for (const [method, field] of [
    ["runtimeEvidence", "runtime"],
    ["interactionEvidence", "interaction"],
    ["readonlyEvidence", "readonly"],
    ["executionEvidence", "execution"],
    ["recoveryEvidence", "recovery"],
    ["isolationEvidence", "isolation"],
    ["visualEvidence", "visualMatrix"],
    ["goalRegressionEvidence", "goalRegression"],
    ["soakEvidence", "soak"],
  ]) {
    driver[method] = async () => {
      calls.push(method);
      return structuredClone(expected[field]);
    };
  }
  assert.deepEqual(
    await collectInteractionPlanAcceptanceReport(driver, { expectedSoakMinutes: 0 }),
    { ...expected, verificationStatus: "VERIFIED" },
  );
  assert.deepEqual(calls, Object.keys(driver));
  await assert.rejects(
    () => collectInteractionPlanAcceptanceReport({ ...driver, recoveryEvidence: undefined }),
    /recoveryEvidence/u,
  );
});

test("fixture 覆盖 single/multi/custom/skip、重启恢复与并发 CAS", () => {
  const fixture = createInteractionPlanStateFixture();
  const questions = [
    { id: "scope", kind: "single", required: true, options: [{ id: "safe", label: "安全" }] },
    {
      id: "targets",
      kind: "multi",
      required: true,
      options: [
        { id: "ui", label: "UI" },
        { id: "api", label: "API" },
      ],
    },
    { id: "note", kind: "custom", required: false, options: [] },
  ];
  const request = fixture.ask({ threadId: "thread_a", questions });
  assert.equal(fixture.restart().length, 1);
  const collapsed = fixture.collapse(request.requestId);
  assert.equal(collapsed.collapsed, true);
  let answer = fixture.answer({
    requestId: request.requestId,
    expectedRevision: request.revision,
    response: { kind: "option", optionId: "safe" },
    idempotencyKey: "answer-scope",
  });
  assert.equal(
    fixture.answer({
      requestId: request.requestId,
      expectedRevision: request.revision,
      response: { kind: "option", optionId: "safe" },
      idempotencyKey: "answer-scope",
    }).revision,
    answer.revision,
  );
  answer = fixture.answer({
    requestId: request.requestId,
    expectedRevision: answer.revision,
    response: { kind: "option", optionId: "ui" },
    idempotencyKey: "answer-targets",
  });
  expectCode(
    () =>
      fixture.answer({
        requestId: request.requestId,
        expectedRevision: request.revision,
        response: { kind: "skip" },
        idempotencyKey: "stale",
      }),
    "INTERACTION_REVISION_CONFLICT",
  );
  answer = fixture.answer({
    requestId: request.requestId,
    expectedRevision: answer.revision,
    response: { kind: "custom", text: "自定义约束" },
    idempotencyKey: "answer-note",
  });
  assert.equal(answer.state, "answered");

  const second = fixture.ask({
    threadId: "thread_b",
    questions: [
      { id: "optional", kind: "single", required: false, options: [{ id: "x", label: "X" }] },
    ],
  });
  const skipped = fixture.answer({
    requestId: second.requestId,
    expectedRevision: second.revision,
    response: { kind: "skip" },
    idempotencyKey: "skip-ok",
  });
  assert.equal(skipped.state, "answered");
});

test("fixture 的原子执行只创建一个 run，成功与 unknown 调用都不盲重放", () => {
  const fixture = createInteractionPlanStateFixture();
  let plan = fixture.createPlan({ threadId: "thread_a", objective: "execute" });
  plan = fixture.saveDraft({
    planId: plan.planId,
    expectedRevision: 0,
    definition: { steps: [{ id: "step" }] },
  });
  plan = fixture.propose({ planId: plan.planId, expectedRevision: plan.revision });
  const revision = plan.currentRevision;
  plan = fixture.execute({
    planId: plan.planId,
    expectedRevision: plan.revision,
    revisionId: revision.revisionId,
    revisionHash: revision.hash,
    idempotencyKey: "execute-once",
  });
  const duplicate = fixture.execute({
    planId: plan.planId,
    expectedRevision: plan.revision,
    revisionId: revision.revisionId,
    revisionHash: revision.hash,
    idempotencyKey: "execute-once",
  });
  assert.equal(duplicate.runCount, 1);
  fixture.recordTool({ planId: plan.planId, callId: "call-success", resultState: "success" });
  fixture.recordTool({ planId: plan.planId, callId: "call-unknown", resultState: "unknown" });
  const recovered = fixture.recover(plan.planId);
  assert.equal(recovered.status, "needs_attention");
  assert.equal(fixture.readPlan(plan.planId).toolLedger.length, 2);
});

test("环境变量清除 Provider secret 并要求显式 staging 证据", () => {
  const old = process.env.JA_REAL_PROVIDER_TOKEN;
  process.env.JA_REAL_PROVIDER_TOKEN = "must-not-propagate";
  try {
    const environment = buildInteractionPlanDesktopEnvironment({
      evidenceDirectory: "C:/evidence",
      sidecarManifest: "C:/sidecar/sidecar-manifest.json",
      sidecarExecutable: "C:/sidecar/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
      soakMinutes: 120,
    });
    assert.equal(environment.JA_E2E_INTERACTION_PLAN_ONLY, "1");
    assert.equal(environment.JA_E2E_INTERACTION_PLAN_SOAK_MINUTES, "120");
    assert.equal(environment.JA_E2E_INTERACTION_PLAN_SCOPE, "production");
    assert.equal(environment.JA_REAL_PROVIDER_TOKEN, undefined);
    assert.equal(environment.JA_E2E_APP_SERVER_JAR, "");
    const functionalEnvironment = buildInteractionPlanDesktopEnvironment({
      evidenceDirectory: "C:/evidence",
      sidecarManifest: "C:/sidecar/sidecar-manifest.json",
      sidecarExecutable: "C:/sidecar/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
      soakMinutes: 0,
      scope: "functional",
    });
    assert.equal(functionalEnvironment.JA_E2E_INTERACTION_PLAN_SOAK_MINUTES, "0");
    assert.equal(functionalEnvironment.JA_E2E_INTERACTION_PLAN_SCOPE, "functional");
  } finally {
    if (old === undefined) delete process.env.JA_REAL_PROVIDER_TOKEN;
    else process.env.JA_REAL_PROVIDER_TOKEN = old;
  }
});

test("参数解析限制 soak 范围并强制 evidence directory", () => {
  const parsed = parseInteractionPlanArguments([
    "--evidence-directory",
    "C:/evidence",
    "--sidecar-directory",
    "C:/sidecar",
    "--soak-minutes",
    "0",
    "--preflight-only",
  ]);
  assert.equal(parsed.preflightOnly, true);
  assert.equal(parsed.soakMinutes, 0);
  assert.throws(() => parseInteractionPlanArguments([]), /evidence-directory/u);
  assert.throws(
    () =>
      parseInteractionPlanArguments([
        "--evidence-directory",
        "C:/evidence",
        "--soak-minutes",
        "1.5",
      ]),
    /soak-minutes/u,
  );
  assert.throws(
    () =>
      parseInteractionPlanArguments(["--evidence-directory", "C:/evidence", "--soak-minutes", "0"]),
    /requires --soak-minutes/u,
  );
  const functional = parseInteractionPlanArguments([
    "--evidence-directory",
    "C:/evidence",
    "--sidecar-directory",
    "C:/sidecar",
    "--functional-only",
  ]);
  assert.equal(functional.functionalOnly, true);
  assert.equal(functional.scope, "functional");
  assert.equal(functional.soakMinutes, 0);
  assert.throws(
    () =>
      parseInteractionPlanArguments([
        "--evidence-directory",
        "C:/evidence",
        "--functional-only",
        "--soak-minutes",
        "120",
      ]),
    /functional-only requires --soak-minutes 0/u,
  );
});

test("functional-only 报告明确跳过长稳且使用独立通过状态", () => {
  const report = validReport();
  markInteractionPlanFunctionalReport(report);
  const validated = validateInteractionPlanAcceptanceReport(report, {
    expectedSoakMinutes: 0,
    scope: "functional",
  });
  assert.equal(validated.scope, "functional");
  assert.deepEqual(validated.soak, {
    status: "not_run",
    requestedMinutes: 0,
    elapsedMs: 0,
    healthChecks: 0,
    healthy: null,
    recoveryExercised: false,
  });
  assert.equal(validated.verificationStatus, "FUNCTIONAL_VERIFIED");
});

test("staged Native sidecar identity 必须 hash/size 一致", async () => {
  const root = await mkdtemp(join(tmpdir(), "ja-interaction-plan-sidecar-"));
  try {
    const sidecars = join(root, "sidecars");
    await mkdir(sidecars, { recursive: true });
    const executable = join(sidecars, "ja-app-server-x86_64-pc-windows-msvc.exe");
    const content = Buffer.from("native-image-fixture", "utf8");
    const sha256 = createHash("sha256").update(content).digest("hex");
    await writeFile(executable, content);
    await writeFile(
      join(root, "sidecar-manifest.json"),
      JSON.stringify({
        product: "Ja",
        nativeImageOnly: true,
        noFallback: true,
        stagingMode: "copy",
        sidecar: {
          relativePath: "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
          sourceArtifact: { sha256, sizeBytes: content.length },
          stagedArtifact: { sha256, sizeBytes: content.length },
        },
      }),
      "utf8",
    );
    const staged = await readStagedInteractionPlanSidecar(root);
    assert.equal(staged.identity.identityMatched, true);
    await writeFile(executable, Buffer.from("tampered", "utf8"));
    await assert.rejects(() => readStagedInteractionPlanSidecar(root), /identity is incomplete/u);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("hook preflight 对未接入新 runner 返回可读缺口，不误报 READY", async () => {
  const root = await mkdtemp(join(tmpdir(), "ja-interaction-plan-hooks-"));
  try {
    const runner = join(root, "runner.mjs");
    await writeFile(runner, "legacy", "utf8");
    const missing = await findMissingInteractionPlanHooks(root, runner);
    assert.ok(missing.some((entry) => entry.startsWith("desktop-v1-mode:")));
    assert.ok(missing.some((entry) => entry.startsWith("interaction-api:")));
    assert.ok(missing.some((entry) => entry.startsWith("accessibility-driver:")));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("WebView2 driver 锁定真实 CDP、Interaction callback 和视觉矩阵接线", async () => {
  const source = await readFile(
    new URL("./interaction-plan-webview2-driver.mjs", import.meta.url),
    "utf8",
  );
  const smokeSource = await readFile(
    new URL("./windows-desktop-smoke.mjs", import.meta.url),
    "utf8",
  );
  assert.match(source, /__TAURI_INTERNALS__\?\.invoke/u);
  assert.match(source, /exerciseInteractionScenario/u);
  assert.match(source, /requiredCallback\(\s*exerciseInteractionScenario/u);
  assert.doesNotMatch(source, /if \(typeof exerciseInteractionScenario/u);
  assert.doesNotMatch(source, /restartApplication/u);
  assert.match(source, /prepareVisualPreferences/u);
  assert.match(source, /assertVisibleState/u);
  assert.match(source, /captureInteractionPlanAccessibility/u);
  assert.match(source, /expectedRequired:\s*true/u);
  assert.match(source, /\.accessibility\.json/u);
  assert.match(source, /screenReaderNarrationVerified: false/u);
  assert.doesNotMatch(source, /Emulation\.setDeviceMetricsOverride/u);
  assert.match(source, /newCDPSession/u);
  assert.match(source, /Page\.captureScreenshot/u);
  assert.match(source, /fromSurface:\s*true/u);
  assert.match(source, /captureBeyondViewport:\s*false/u);
  assert.doesNotMatch(source, /page\.screenshot/u);
  assert.match(source, /zoom/u);
  assert.match(source, /cssViewportWidth/u);
  assert.match(source, /nativeWidth/u);
  assert.match(source, /nativeHeight/u);
  assert.match(source, /pngDimensionsMatchNativeViewport/u);
  assert.match(source, /questionComposerOverlapCount/u);
  assert.match(source, /native_webview_zoom/u);
  assert.doesNotMatch(source, /deviceScaleFactor: scale/u);
  assert.match(source, /prefers-reduced-motion|reducedMotion/u);
  assert.match(source, /reducedTransparency/u);
  assert.match(source, /highContrast/u);
  assert.match(source, /questionTextLength/u);
  assert.match(source, /accessibleName/u);
  assert.match(source, /questionCardCount, 1/u);
  assert.match(source, /surfaceMode/u);
  assert.match(source, /surfacePhase/u);
  assert.match(source, /revealPlanSurface/u);
  assert.match(source, /revealPlanAnchor/u);
  assert.match(source, /planObjectiveVisible/u);
  assert.match(source, /planActionVisible/u);
  assert.match(source, /plan-content/u);
  assert.match(source, /plan-action/u);
  assert.match(source, /收起问题/u);
  assert.match(source, /展开问题/u);
  assert.match(source, /ja-interaction-card/u);
  assert.match(source, /ja-plan-workbench/u);
  assert.match(smokeSource, /__JA_E2E_INTERACTION_FEEDBACK__/u);
  assert.match(smokeSource, /performance\.now\(\)/u);
  assert.match(smokeSource, /requestAnimationFrame/u);
  assert.match(smokeSource, /feedbackLatencyMs/u);
  assert.match(smokeSource, /interactionFeedback\.latencyMs >= 100/u);
});

/** focused Interaction/Plan 配置直接返回扁平 Native identity，driver 必须接收完整报告字段。 */
test("focused Interaction/Plan runner 传递扁平 Native sidecar identity", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /nativeSidecar,\s*screenshotDirectory:/u);
  assert.doesNotMatch(source, /nativeSidecar:\s*nativeSidecar\.identity,\s*screenshotDirectory:/u);
});

/** 隔离 Gate 必须按资源 owner 归因 invoke，并排除验收自身的权威 Thread read。 */
test("隔离 invoke 证据按 Thread/Plan owner 归因而非使用全局计数", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /ownerIdentityObserved/u);
  assert.match(source, /threadId: typeof call\.threadId === "string"/u);
  assert.match(source, /planId: typeof call\.planId === "string"/u);
  assert.match(source, /explicitAuthorityRead/u);
  assert.match(source, /explicitAuthorityReadTraceKeys/u);
  assert.match(source, /hiddenPlanInvokeDeltaByOwner/u);
  assert.match(source, /expectedOwnerEventCount/u);
  assert.match(source, /hiddenPlanOwnerDeltaClear/u);
  assert.match(source, /hiddenPlanInvokeDeltaByOwner\[owner\]\[command\] \+= 1/u);
});

/** 视觉矩阵面对长历史时必须滚动真实 Timeline，并在缺卡时保留权威读模型诊断。 */
test("视觉矩阵先滚动真实 Timeline 再等待虚拟化 Plan 卡片", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /page\.locator\("\.ja-chat-timeline__scroll"\)\.first\(\)/u);
  assert.match(source, /scrollport\.scrollTo\(\{ top: maximum, behavior: "auto" \}\)/u);
  assert.match(source, /ja_runtime_plan_current_read/u);
  assert.match(source, /ja_runtime_plan_read/u);
  assert.match(source, /matchingPlanCardCount/u);
  assert.match(source, /materializedRows/u);
});

/** 几何回归区分 raw rect 相交与 viewport/overflow 裁剪后的真实绘制相交。 */
test("视觉几何裁剪不误报，真实可见控件重叠必须失败", () => {
  const viewport = { left: 0, top: 0, right: 320, bottom: 240 };
  const clipped = {
    controlId: "radio-clipped",
    surfaceIndex: 0,
    name: "被滚动裁剪的选项",
    rect: { left: 20, top: 0, right: 180, bottom: 180 },
    clipping: [
      { rect: { left: 0, top: 0, right: 320, bottom: 80 }, source: { className: "scrollport" } },
    ],
  };
  const outside = {
    controlId: "footer-outside",
    surfaceIndex: 0,
    name: "被裁剪区域外的页脚",
    rect: { left: 100, top: 120, right: 240, bottom: 220 },
    clipping: [
      { rect: { left: 0, top: 0, right: 320, bottom: 240 }, source: { className: "surface" } },
    ],
  };
  const clippedResult = computeVisibleControlOverlaps([clipped, outside], viewport);
  assert.equal(computePaintedRect(clipped, viewport).bottom, 80);
  assert.equal(clippedResult.overlaps.length, 0);

  const visibleLeft = {
    controlId: "left",
    surfaceIndex: 0,
    name: "左侧控件",
    rect: { left: 20, top: 20, right: 160, bottom: 100 },
    clipping: [],
  };
  const visibleRight = {
    controlId: "right",
    surfaceIndex: 0,
    name: "右侧控件",
    rect: { left: 100, top: 60, right: 240, bottom: 140 },
    clipping: [],
  };
  const overlapResult = computeVisibleControlOverlaps([visibleLeft, visibleRight], viewport);
  assert.equal(overlapResult.overlaps.length, 1);
  assert.deepEqual(
    [overlapResult.overlaps[0].left.name, overlapResult.overlaps[0].right.name],
    ["左侧控件", "右侧控件"],
  );
  assert.equal(overlapResult.overlaps[0].intersection.width, 60);
  assert.equal(overlapResult.overlaps[0].intersection.height, 40);
});

test("恢复清理等待权威 suspended Turn 后再开放下一次 admission", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /async function cancelRecoveredSuspendedTurns/u);
  assert.match(source, /recovered Turn settlement/u);
  assert.match(source, /candidate\.turnId === turn\.turnId && candidate\.status === "cancelled"/u);
  assert.match(source, /await waitForComposerAdmission\(page, recoveryDeadline\)/u);
  assert.doesNotMatch(source, /const recoveredThread = await readPlanGoalThreadAuthority/u);
});

/** 完成 Goal 后的 Interaction 请求不能读取旧 Goal binding，避免共享 fixture context 串线成 500。 */
test("完成 Goal 后 Interaction 请求跳过旧 Goal binding 解析", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(
    source,
    /const currentGoalBindingRevision =\s*scenario\.id === scenarios\.planGoal\.id &&\s*!planGoalEvaluationRequest &&\s*planGoalContext\?\.kind === "goal"[\s\S]*?readGoalToolBindingRevision/u,
  );
  assert.match(
    source,
    /const planGoalAggregateRevision =\s*scenario\.id === scenarios\.planGoal\.id[\s\S]*?: undefined;\s*if \(\s*scenario\.id === scenarios\.planGoal\.id/u,
  );
});

/** 隔离 Goal 的 pause/resume 与 Goal-only completion 必须从最新 projection 做 CAS，防止后台 Provider 伪造成功。 */
test("隔离 Goal pause/resume 与 Goal-only 完成使用真实 fixture identity", async () => {
  const source = await readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8");
  assert.match(
    source,
    /providerFixture\.resetPlanGoalContext\("normal"\);[\s\S]*?objective: providerFixture\.scenarios\.planGoal\.goalOnlyObjective/u,
  );
  assert.match(
    source,
    /const isolationCriterionId = providerFixture\.scenarios\.planGoal\.goalOnlyCriterionId/u,
  );
  assert.match(source, /runGoalOnlyCompletion\(/u);
  assert.match(source, /completionProof: goalOnlyCompletion\.completionProof/u);
  assert.match(source, /async \(command, goalId, expectedStatus, action\) =>/u);
  assert.match(source, /invoke\("ja_runtime_goal_read", \{ goalId \}\)/u);
  assert.match(source, /latestGoal\.status !== expectedStatus/u);
  assert.match(source, /lastResult\.code !== "GOAL_REVISION_CONFLICT"/u);
  assert.match(source, /kind: "goal"[\s\S]*?planRevisionId: null/u);
  assert.match(source, /goalRevision: goalResumed\.value\.goal\.revision/u);
});

test("loopback Provider 场景只发结构化 Interaction Tool 且身份稳定", () => {
  const questionIds = interactionPlanProviderScenario.questions.map(
    (question) => question.questionId,
  );
  assert.deepEqual(questionIds, ["question_scope", "question_targets", "question_note"]);
  assert.match(interactionPlanToolStream(1), /request_user_input/u);
  assert.match(interactionPlanToolStream(1), /option_ui/u);
  assert.match(interactionPlanTextStream(2), /Interaction Plan/u);
  assert.equal(
    interactionPlanProviderScenario.questions.filter((question) => question.required).length,
    2,
  );
});
