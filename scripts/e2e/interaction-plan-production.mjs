// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 完整 Plan v2 的 Windows 真窗验收合同。
 *
 * 这个 runner 只描述可观察证据，不实现生产状态机。生产 runner 必须把真实
 * Tauri/WebView2、隔离 Native Image sidecar 和确定性 Provider 的结果投影到这里，
 * 这样 source/static 检查不能伪造生产通过，且 Interaction 与 Goal/Plan 的边界保持清晰。
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, realpath, stat, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export const INTERACTION_PLAN_CONTRACT_VERSION = 1;

export const INTERACTION_PLAN_RPC_CONTRACT = Object.freeze({
  methods: Object.freeze({
    interactionRead: "interaction/read",
    interactionObserve: "interaction/observe",
    interactionUnobserve: "interaction/unobserve",
    interactionRespond: "interaction/respond",
    interactionCancel: "interaction/cancel",
    planRead: "plan/read",
    planDraftSave: "plan/draft/save",
    planPropose: "plan/propose",
    planExecute: "plan/execute",
    planPause: "plan/pause",
    planResume: "plan/resume",
    planStop: "plan/stop",
    goalRead: "goal/read",
  }),
  commands: Object.freeze({
    interactionRead: "ja_runtime_interaction_read",
    interactionObserve: "ja_runtime_interaction_observe",
    interactionUnobserve: "ja_runtime_interaction_unobserve",
    interactionRespond: "ja_runtime_interaction_respond",
    interactionCancel: "ja_runtime_interaction_cancel",
    planRead: "ja_runtime_plan_read",
    planCreate: "ja_runtime_plan_create",
    planDraftSave: "ja_runtime_plan_draft_save",
    planPropose: "ja_runtime_plan_propose",
    planExecute: "ja_runtime_plan_execute",
    planPause: "ja_runtime_plan_pause",
    planResume: "ja_runtime_plan_resume",
    planStop: "ja_runtime_plan_stop",
    goalRead: "ja_runtime_goal_read",
  }),
  errors: Object.freeze({
    revisionConflict: "INTERACTION_REVISION_CONFLICT",
    staleRequest: "INTERACTION_INVALID_STATE",
    planRevisionConflict: "PLAN_REVISION_CONFLICT",
    unknownSideEffect: "PLAN_INVALID_STATE",
  }),
});

export const INTERACTION_PLAN_UI_CONTRACT = Object.freeze({
  questionRoot: '.ja-interaction-card[data-interaction-status="pending"]',
  summaryRoot: '.ja-interaction-card[aria-label="已回答的问题"]',
  planRoot: ".ja-plan-timeline",
  executionRoot: ".ja-plan-workbench",
  widths: Object.freeze([799, 800]),
  zooms: Object.freeze([100, 125, 150, 200]),
  themes: Object.freeze(["light", "dark", "system"]),
  longTextThreshold: 80,
  requiredQuestionKinds: Object.freeze(["single", "multi", "custom", "skip"]),
});

function isObject(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function assertBooleanRecord(record, keys, label) {
  assert.equal(isObject(record), true, `${label} must be an object`);
  for (const key of keys) assert.equal(typeof record[key], "boolean", `${label}.${key}`);
}

/**
 * 校验真实 question 帧的 AX 子事实；Narrator 实际朗读属于独立系统验收，不能把
 * `screenReaderNarrationVerified=false` 当作本合同的完成失败或成功依据。
 */
function assertInteractionPlanAccessibilityEvidence(evidence) {
  assert.equal(isObject(evidence), true, "visualMatrix question accessibilityEvidence must be an object");
  assert.equal(evidence.version, 1);
  assert.equal(evidence.surface, "webview2_cdp");
  assert.equal(evidence.source, "Accessibility.getFullAXTree");
  assert.equal(typeof evidence.fileName, "string");
  assert.match(evidence.fileName, /^[^\\/]+\.accessibility\.json$/u);
  assert.equal(evidence.screenReaderNarrationVerified, false);
  assert.equal(Number.isSafeInteger(evidence.axNodeCount) && evidence.axNodeCount > 0, true);
  assert.equal(Number.isSafeInteger(evidence.controlCount) && evidence.controlCount > 0, true);
  assert.equal(isObject(evidence.question), true);
  assert.equal(evidence.question.label?.present, true);
  assert.equal(evidence.question.groupLabel?.present, true);
  assert.equal(["group", "radiogroup"].includes(evidence.question.groupRole), true);
  assert.equal(typeof evidence.question.required, "boolean");
  assert.equal(Array.isArray(evidence.controls), true);
  assert.equal(evidence.controls.length, evidence.controlCount);
  for (const control of evidence.controls) {
    assert.equal(["radio", "checkbox"].includes(control?.role), true);
    assert.equal(control?.accessibleName?.present, true);
    assert.equal([true, false, "mixed"].includes(control?.checked), true);
    assert.equal(typeof control?.required, "boolean");
  }
  assert.equal(isObject(evidence.roleCounts), true);
  assert.equal(Number.isSafeInteger(evidence.roleCounts.radio), true);
  assert.equal(Number.isSafeInteger(evidence.roleCounts.checkbox), true);
  assert.equal(
    evidence.roleCounts.radio + evidence.roleCounts.checkbox,
    evidence.controlCount,
  );
}

/** 校验无 Plan Goal 的完成证据必须来自同一 Goal definition revision 与 Run，且只产生一次 achieved。 */
function assertGoalOnlyCompletionProof(proof) {
  assert.equal(isObject(proof), true, "goalRegression.completionProof must be an object");
  assert.equal(proof.achieved, true);
  assert.equal(proof.planLinkNull, true);
  assert.equal(proof.completionGateVerified, true);
  assert.equal(typeof proof.goalId, "string");
  assert.equal(Number.isSafeInteger(proof.goalDefinitionRevision), true);
  assert.equal(typeof proof.runId, "string");
  const identity = (value, label) => {
    assert.equal(isObject(value), true, `${label} must be an object`);
    assert.equal(value.goalId, proof.goalId, `${label}.goalId`);
    assert.equal(
      value.goalDefinitionRevision,
      proof.goalDefinitionRevision,
      `${label}.goalDefinitionRevision`,
    );
    assert.equal(value.runId, proof.runId, `${label}.runId`);
  };
  identity(proof.evidence, "goalRegression.completionProof.evidence");
  assert.equal(proof.evidence.count > 0, true);
  assert.equal(
    Array.isArray(proof.evidence.criterionIds) && proof.evidence.criterionIds.length > 0,
    true,
  );
  assert.equal(
    Array.isArray(proof.evidence.sourceIds) && proof.evidence.sourceIds.length > 0,
    true,
  );
  identity(proof.evaluation, "goalRegression.completionProof.evaluation");
  assert.equal(proof.evaluation.count > 0, true);
  assert.equal(proof.evaluation.metCount > 0, true);
  assert.equal(typeof proof.evaluation.evaluationId, "string");
  assert.equal(proof.evaluation.verdict, "MET");
  identity(proof.completionEvent, "goalRegression.completionProof.completionEvent");
  assert.equal(proof.completionEvent.count, 1);
  assert.equal(typeof proof.completionEvent.eventId, "string");
  assert.equal(Number.isSafeInteger(proof.completionEvent.eventSequence), true);
  assert.equal(proof.pendingToolCount, 0);
  assert.equal(proof.pendingInteractionCount, 0);
}

/**
 * 校验完整 Plan 真窗报告。每个字段都对应一个用户可复现的闭环，缺任何阶段都拒绝通过。
 * 生产模式默认要求 120 分钟 soak；functional-only 由调用方显式传入 scope，
 * 只验证同一功能链并把未执行长稳明确标记为 not_run。
 */
export function validateInteractionPlanAcceptanceReport(
  report,
  { expectedSoakMinutes = 120, scope = "production" } = {},
) {
  assert.equal(isObject(report), true, "Interaction/Plan report must be an object");
  assert.equal(["production", "functional"].includes(scope), true);
  assert.equal(report.contractVersion, INTERACTION_PLAN_CONTRACT_VERSION);
  assert.equal(report.runtime?.surface, "tauri_webview2");
  assert.equal(report.runtime?.nativeWindow, true);
  assert.equal(report.runtime?.deterministicMockProvider, true);
  assert.equal(report.runtime?.realProviderRequests, 0);
  assert.equal(report.runtime?.nativeSidecar?.used, true);
  assert.equal(report.runtime?.nativeSidecar?.identityMatched, true);
  assert.equal(report.runtime?.nativeSidecar?.noFallback, true);

  assertBooleanRecord(
    report.interaction,
    [
      "singleChoice",
      "multiChoice",
      "customAnswer",
      "explicitSkip",
      "collapsePreservedDraft",
      "restartRestoredPendingRequest",
      "duplicateResponseIdempotent",
      "staleResponseRejected",
      "cancelDoesNotResumeTurn",
      "raceSingleWinner",
      "imeCompositionSafe",
      "keyboardComplete",
      "feedbackMeasuredInBrowser",
    ],
    "interaction",
  );
  assert.deepEqual(
    [...new Set(report.interaction.questionKinds)].sort(),
    [...INTERACTION_PLAN_UI_CONTRACT.requiredQuestionKinds].sort(),
  );
  assert.equal(report.interaction.pendingThreadCount, 1);
  assert.equal(report.interaction.answerSummaryCollapsed, true);
  assert.equal(report.interaction.noImplicitDefault, true);
  assert.equal(report.interaction.feedbackEventType, "change");
  assert.equal(Number.isFinite(report.interaction.feedbackLatencyMs), true);
  assert.equal(
    report.interaction.feedbackLatencyMs >= 0 && report.interaction.feedbackLatencyMs < 100,
    true,
  );
  assert.equal(isObject(report.interaction.feedbackReadback), true);
  assert.equal(report.interaction.feedbackReadback.checked, true);
  assert.equal(typeof report.interaction.feedbackReadback.value, "string");
  assert.equal(report.interaction.feedbackReadback.value.length > 0, true);
  assert.equal(report.interaction.feedbackReadback.optionId, "option_ui");

  assertBooleanRecord(
    report.readonly,
    [
      "shellRejected",
      "workspaceWriteRejected",
      "externalWriteRejected",
      "subagentWriteRejected",
      "readOnlyToolsOnly",
      "executionBoundaryRequired",
    ],
    "readonly",
  );

  assertBooleanRecord(
    report.execution,
    [
      "atomicExecute",
      "executionAuthorizationRecorded",
      "singleRunCreated",
      "continuationKeptBudget",
      "pauseSettledBeforeVisible",
      "resumeUsedSameRun",
      "stopPreservedHistory",
      "versionConflictDidNotExecuteLatest",
      "goalNotImplicitlyCreated",
    ],
    "execution",
  );
  assert.equal(report.execution.runCount, 1);
  assert.equal(report.execution.completionProof?.completionGateVerified, true);
  assert.equal(report.execution.completionProof?.completionEventCount, 1);
  assert.equal(report.execution.completionProof?.turnCount >= 2, true);
  assert.equal(report.execution.completionProof?.notMetEvaluationCount >= 1, true);
  assert.equal(report.execution.completionProof?.metEvaluationCount >= 1, true);
  assert.equal(report.execution.continuationTurnCount >= 2, true);
  assert.equal(report.execution.remainingBudgetNonNegative, true);

  assertBooleanRecord(
    report.recovery,
    [
      "restartRestoredSnapshot",
      "unknownSideEffectPaused",
      "unknownSideEffectNotReplayed",
      "successfulToolNotReplayed",
      "lateEventIgnored",
      "resumeExplicitlyRequired",
    ],
    "recovery",
  );
  assert.equal(["needs_attention", "paused", "stopped"].includes(report.recovery.status), true);

  assertBooleanRecord(
    report.isolation,
    [
      "goalWithoutPlan",
      "planWithoutGoal",
      "threadAQuestionNotVisibleInThreadB",
      "threadBQuestionNotVisibleInThreadA",
      "hiddenThreadDidNotMaterializePlan",
    ],
    "isolation",
  );

  const frames = report.visualMatrix;
  assert.equal(
    Array.isArray(frames) &&
      frames.length >=
        INTERACTION_PLAN_UI_CONTRACT.widths.length * INTERACTION_PLAN_UI_CONTRACT.zooms.length * 3,
    true,
  );
  assert.deepEqual(
    [...new Set(frames.map((frame) => frame.theme))].sort(),
    [...INTERACTION_PLAN_UI_CONTRACT.themes].sort(),
  );
  for (const width of INTERACTION_PLAN_UI_CONTRACT.widths) {
    for (const zoom of INTERACTION_PLAN_UI_CONTRACT.zooms) {
      const matchingFrames = frames.filter((frame) => frame.width === width && frame.zoom === zoom);
      assert.equal(
        matchingFrames.some((frame) => frame.surfaceMode === "question"),
        true,
        `missing question frame ${width}@${zoom}%`,
      );
      assert.equal(
        matchingFrames.some((frame) => frame.surfaceMode === "plan"),
        true,
        `missing plan frame ${width}@${zoom}%`,
      );
      assert.equal(
        matchingFrames.some(
          (frame) => frame.surfaceMode === "plan" && frame.surfacePhase === "content",
        ),
        true,
        `missing plan content frame ${width}@${zoom}%`,
      );
      assert.equal(
        matchingFrames.some(
          (frame) => frame.surfaceMode === "plan" && frame.surfacePhase === "action",
        ),
        true,
        `missing plan action frame ${width}@${zoom}%`,
      );
      if (zoom === 200) {
        assert.equal(
          matchingFrames.some(
            (frame) => frame.surfaceMode === "question" && frame.longText === true,
          ),
          true,
          `missing long-text question frame ${width}@${zoom}%`,
        );
      }
    }
  }
  assert.equal(
    frames.some((frame) => frame.reducedMotion === true),
    true,
  );
  assert.equal(
    frames.some((frame) => frame.reducedTransparency === true),
    true,
  );
  assert.equal(
    frames.some((frame) => frame.highContrast === true),
    true,
  );
  assert.equal(
    frames.some((frame) => frame.longText === true),
    true,
  );
  for (const frame of frames) {
    assert.equal(["question", "plan"].includes(frame.surfaceMode), true);
    assert.equal(
      frame.surfacePhase === "question" ||
        frame.surfacePhase === "content" ||
        frame.surfacePhase === "action",
      true,
    );
    assert.equal(INTERACTION_PLAN_UI_CONTRACT.themes.includes(frame.theme), true);
    assert.equal(INTERACTION_PLAN_UI_CONTRACT.zooms.includes(frame.zoom), true);
    assert.equal(frame.nativeWidth, frame.width);
    assert.equal(Number.isSafeInteger(frame.nativeHeight) && frame.nativeHeight > 0, true);
    assert.equal(Number.isSafeInteger(frame.cssViewportWidth), true);
    assert.equal(frame.cssViewportWidth > 0, true);
    assert.equal(frame.zoomEvidence, "native_webview_zoom");
    assert.equal(frame.resolvedTheme === "light" || frame.resolvedTheme === "dark", true);
    assert.equal(frame.mediaReducedMotion, frame.reducedMotion);
    assert.equal(frame.documentOverflow, 0);
    assert.equal(frame.horizontalOverflow, 0);
    assert.equal(frame.controlOverlapCount, 0);
    assert.equal(Number.isSafeInteger(frame.questionComposerOverlapCount), true);
    assert.equal(frame.questionComposerOverlapCount, 0);
    assert.deepEqual(frame.questionComposerOverlaps, []);
    assert.equal(frame.unnamedControlCount, 0);
    assert.equal(frame.composerInViewport, true);
    assert.equal(Number.isFinite(frame.devicePixelRatio) && frame.devicePixelRatio > 0, true);
    assert.equal(Number.isSafeInteger(frame.pngWidth) && frame.pngWidth > 0, true);
    assert.equal(Number.isSafeInteger(frame.pngHeight) && frame.pngHeight > 0, true);
    assert.equal(Number.isSafeInteger(frame.pngByteLength) && frame.pngByteLength > 24, true);
    assert.equal(frame.pngDimensionsMatchNativeViewport, true);
    assert.equal(frame.pngWidth, frame.nativeWidth);
    assert.equal(frame.pngHeight, frame.nativeHeight);
    assert.equal(Number.isSafeInteger(frame.planControlCount), true);
    assert.equal(typeof frame.planObjectiveVisible, "boolean");
    assert.equal(typeof frame.planActionVisible, "boolean");
    assert.equal(frame.planActionCount <= 1, true);
    assert.equal(
      frame.questionTextLength >= INTERACTION_PLAN_UI_CONTRACT.longTextThreshold ||
        frame.longText === false,
      true,
    );
    assert.equal(Number.isSafeInteger(frame.pendingQuestionCount), true);
    assert.equal(Number.isSafeInteger(frame.collapsedQuestionCount), true);
    if (frame.surfaceMode === "question") {
      assert.equal(frame.surfacePhase, "question");
      assert.equal(frame.questionCardCount, 1);
      assert.equal(frame.pendingQuestionCount, 1);
      assert.equal(frame.collapsedQuestionCount, 0);
      assertInteractionPlanAccessibilityEvidence(frame.accessibilityEvidence);
    } else {
      assert.equal(frame.surfacePhase === "content" || frame.surfacePhase === "action", true);
      assert.equal(frame.questionCardCount, 0);
      assert.equal(frame.pendingQuestionCount, 0);
      assert.equal(frame.collapsedQuestionCount, 1);
      assert.equal(frame.planSurfaceCount > 0, true);
      assert.equal(frame.planControlCount > 0, true);
      if (frame.surfacePhase === "content") {
        assert.equal(frame.planObjectiveVisible, true);
        assert.equal(isObject(frame.planObjectiveRect), true);
      } else {
        assert.equal(frame.planActionVisible, true);
        assert.equal(isObject(frame.planActionRect), true);
        assert.equal(frame.planActionCount > 0, true);
      }
      assert.equal(frame.accessibilityEvidence, null);
    }
  }

  assert.deepEqual(report.goalRegression?.statuses, ["active", "paused", "active"]);
  assert.equal(report.goalRegression?.planLinkUnchanged, true);
  assertGoalOnlyCompletionProof(report.goalRegression?.completionProof);
  if (scope === "functional") {
    assert.equal(expectedSoakMinutes, 0);
    assert.equal(report.scope, "functional");
    assert.equal(report.soak?.status, "not_run");
    assert.equal(report.soak?.requestedMinutes, 0);
    assert.equal(report.soak?.elapsedMs, 0);
    assert.equal(report.soak?.healthChecks, 0);
    assert.equal(report.soak?.healthy, null);
    assert.equal(report.soak?.recoveryExercised, false);
  } else {
    assert.equal(report.soak?.requestedMinutes, expectedSoakMinutes);
    assert.equal(report.soak?.elapsedMs >= expectedSoakMinutes * 60_000, true);
    assert.equal(report.soak?.healthChecks >= expectedSoakMinutes * 2, true);
    assert.equal(report.soak?.healthy, true);
    if (expectedSoakMinutes > 0) assert.equal(report.soak?.recoveryExercised, true);
  }
  const booleanGroups = [
    report.interaction,
    report.readonly,
    report.execution,
    report.recovery,
    report.isolation,
  ];
  const allBooleanChecksPassed = booleanGroups.every((group) =>
    Object.values(group)
      .filter((value) => typeof value === "boolean")
      .every((value) => value === true),
  );
  report.verificationStatus = allBooleanChecksPassed
    ? scope === "functional"
      ? "FUNCTIONAL_VERIFIED"
      : "VERIFIED"
    : "NOT_VERIFIED";
  return report;
}

/** 将同一真实功能链的零分钟运行标为 functional，避免把一次快速检查误报成长稳通过。 */
export function markInteractionPlanFunctionalReport(report) {
  if (!isObject(report)) throw new TypeError("Interaction/Plan report must be an object");
  if (report.soak?.requestedMinutes !== 0) {
    throw new Error("functional-only report requires a zero-minute soak path");
  }
  report.scope = "functional";
  report.soak = {
    status: "not_run",
    requestedMinutes: 0,
    elapsedMs: 0,
    healthChecks: 0,
    healthy: null,
    recoveryExercised: false,
  };
  return report;
}

/**
 * 为 focused Windows 进程构造隔离环境，清空所有 Provider secret 与 JAR fallback。
 * 证据和截图只允许进入显式临时目录，防止验收污染源码树或真实用户配置。
 */
export function buildInteractionPlanDesktopEnvironment({
  evidenceDirectory,
  sidecarManifest,
  sidecarExecutable,
  soakMinutes = 120,
  scope = "production",
}) {
  if (!["production", "functional"].includes(scope))
    throw new Error("interaction-plan scope must be production or functional");
  const environment = {
    ...process.env,
    JA_E2E_INTERACTION_PLAN_ONLY: "1",
    JA_E2E_INTERACTION_PLAN_CONTRACT_VERSION: String(INTERACTION_PLAN_CONTRACT_VERSION),
    JA_E2E_INTERACTION_PLAN_REPORT: join(evidenceDirectory, "interaction-plan-report.json"),
    JA_E2E_INTERACTION_PLAN_SOAK_MINUTES: String(soakMinutes),
    JA_E2E_INTERACTION_PLAN_SCOPE: scope,
    JA_E2E_INTERACTION_PLAN_SIDECAR_MANIFEST: sidecarManifest,
    JA_E2E_INTERACTION_PLAN_SIDECAR_EXECUTABLE: sidecarExecutable,
    JA_E2E_SCREENSHOT_DIR: join(evidenceDirectory, "screenshots"),
    JA_E2E_REAL_PROVIDER: "0",
    JA_E2E_REAL_PROVIDER_API_KEY: "",
    JA_E2E_APP_SERVER_JAR: "",
  };
  for (const name of Object.keys(environment)) {
    if (/^JA_(?:E2E_)?REAL_PROVIDER_/u.test(name)) delete environment[name];
  }
  environment.JA_E2E_REAL_PROVIDER = "0";
  environment.JA_E2E_REAL_PROVIDER_API_KEY = "";
  return environment;
}

/** 检查生产 runner、Tauri API 和真实交互 UI 的必要接线，缺失时返回机器可读标签。 */
export async function findMissingInteractionPlanHooks(root = repoRoot, desktopRunner) {
  const checks = [
    {
      label: "desktop-v1-mode",
      path: desktopRunner ?? join(root, "scripts", "e2e", "windows-desktop-smoke.mjs"),
      tokens: [
        "JA_E2E_INTERACTION_PLAN_CONTRACT_VERSION",
        "JA_E2E_INTERACTION_PLAN_REPORT",
        "JA_E2E_INTERACTION_PLAN_SIDECAR_MANIFEST",
        "JA_E2E_INTERACTION_PLAN_SIDECAR_EXECUTABLE",
        "runIndependentInteractionPlanAcceptanceSession",
        "collectInteractionPlanAcceptanceReport",
        "assertVisibleState",
        "native_webview_zoom",
      ],
    },
    {
      label: "interaction-api",
      path: join(root, "apps", "desktop", "src", "api", "tauri", "interaction.ts"),
      tokens: [
        "ja_runtime_interaction_read",
        "ja_runtime_interaction_respond",
        "ja_runtime_interaction_cancel",
      ],
    },
    {
      label: "accessibility-driver",
      path: join(root, "scripts", "e2e", "interaction-plan-webview2-driver.mjs"),
      tokens: [
        "captureInteractionPlanAccessibility",
        ".accessibility.json",
        "screenReaderNarrationVerified",
      ],
    },
    {
      label: "question-card",
      path: join(root, "apps", "desktop", "src", "features", "conversation"),
      tokens: ["ja-interaction-card", "交互问题", "已回答的问题", "<input"],
    },
    {
      label: "plan-execution",
      path: join(root, "apps", "desktop", "src", "features", "goals"),
      tokens: ["ja-plan-timeline", "ja-plan-workbench", "执行"],
    },
  ];
  const missing = [];
  for (const check of checks) {
    let source;
    try {
      if (check.path.endsWith("conversation") || check.path.endsWith("goals")) {
        source = "";
        const { readdir } = await import("node:fs/promises");
        const entries = await readdir(check.path, { recursive: true, withFileTypes: true });
        for (const entry of entries) {
          if (!entry.isFile() || !/\.(?:ts|tsx)$/u.test(entry.name)) continue;
          source += await readFile(join(entry.parentPath ?? check.path, entry.name), "utf8");
        }
      } else {
        source = await readFile(check.path, "utf8");
      }
    } catch {
      missing.push(`${check.label}:file`);
      continue;
    }
    for (const token of check.tokens)
      if (!source.includes(token)) missing.push(`${check.label}:${token}`);
  }
  return missing;
}

/** 解析 focused runner 参数；生产模式必须显式给出证据目录和 sidecar staging 目录。 */
export function parseInteractionPlanArguments(argv) {
  const parsed = {
    evidenceDirectory: undefined,
    sidecarDirectory: undefined,
    desktopRunner: join(repoRoot, "scripts", "e2e", "windows-desktop-smoke.mjs"),
    soakMinutes: 120,
    functionalOnly: false,
    scope: "production",
    preflightOnly: false,
  };
  let soakMinutesArgumentSeen = false;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--preflight-only") {
      parsed.preflightOnly = true;
      continue;
    }
    if (argument === "--functional-only") {
      parsed.functionalOnly = true;
      parsed.scope = "functional";
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") parsed.evidenceDirectory = resolve(value);
    else if (argument === "--sidecar-directory") parsed.sidecarDirectory = resolve(value);
    else if (argument === "--desktop-runner") parsed.desktopRunner = resolve(value);
    else if (argument === "--soak-minutes") {
      soakMinutesArgumentSeen = true;
      parsed.soakMinutes = Number(value);
      if (
        !Number.isSafeInteger(parsed.soakMinutes) ||
        parsed.soakMinutes < 0 ||
        parsed.soakMinutes > 1_440
      ) {
        throw new Error("--soak-minutes must be an integer from 0 to 1440");
      }
    } else throw new Error(`unknown argument ${argument}`);
    index += 1;
  }
  if (parsed.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (parsed.functionalOnly) {
    if (soakMinutesArgumentSeen && parsed.soakMinutes !== 0) {
      throw new Error("--functional-only requires --soak-minutes 0 when the option is provided");
    }
    parsed.soakMinutes = 0;
  } else if (!parsed.preflightOnly && parsed.soakMinutes < 120) {
    throw new Error(
      "production acceptance requires --soak-minutes >= 120; use --functional-only for a zero-minute functional check",
    );
  }
  return parsed;
}

async function sha256File(path) {
  const digest = createHash("sha256");
  await new Promise((resolveHash, rejectHash) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => digest.update(chunk));
    stream.once("error", rejectHash);
    stream.once("end", resolveHash);
  });
  return digest.digest("hex");
}

/** 校验 sidecar manifest、复制产物 hash/size 与 containment，拒绝 JAR 或路径逃逸回退。 */
export async function readStagedInteractionPlanSidecar(sidecarDirectory) {
  const manifestPath = join(sidecarDirectory, "sidecar-manifest.json");
  let manifest;
  try {
    manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  } catch {
    throw new Error("staged interaction-plan sidecar manifest is missing or malformed");
  }
  const relativePath = manifest?.sidecar?.relativePath;
  const expectedRelativePath = "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe";
  const source = manifest?.sidecar?.sourceArtifact;
  const staged = manifest?.sidecar?.stagedArtifact;
  if (
    manifest?.product !== "Ja" ||
    manifest?.nativeImageOnly !== true ||
    manifest?.noFallback !== true ||
    manifest?.stagingMode !== "copy" ||
    relativePath !== expectedRelativePath ||
    source?.sha256 !== staged?.sha256 ||
    source?.sizeBytes !== staged?.sizeBytes
  )
    throw new Error("staged manifest does not prove a copied no-fallback Ja Native Image");
  const [resolvedDirectory, executable] = await Promise.all([
    realpath(sidecarDirectory),
    realpath(resolve(sidecarDirectory, ...relativePath.split("/"))),
  ]);
  const containment = relative(resolvedDirectory, executable);
  if (containment.startsWith("..") || isAbsolute(containment))
    throw new Error("sidecar path escaped staging");
  const metadata = await stat(executable);
  if (
    !metadata.isFile() ||
    metadata.size !== staged.sizeBytes ||
    (await sha256File(executable)) !== staged.sha256
  ) {
    throw new Error("staged sidecar executable identity is incomplete");
  }
  return {
    manifestPath,
    executable,
    identity: {
      used: true,
      identityMatched: true,
      noFallback: true,
      fileName: relativePath.split("/").at(-1),
      sha256: staged.sha256,
      sizeBytes: staged.sizeBytes,
    },
  };
}

export async function collectInteractionPlanAcceptanceReport(
  driver,
  { expectedSoakMinutes = 120, scope = "production" } = {},
) {
  if (!isObject(driver)) throw new TypeError("Interaction/Plan acceptance driver is required");
  const stages = [
    ["runtimeEvidence", "runtime"],
    ["interactionEvidence", "interaction"],
    ["readonlyEvidence", "readonly"],
    ["executionEvidence", "execution"],
    ["recoveryEvidence", "recovery"],
    ["isolationEvidence", "isolation"],
    ["visualEvidence", "visualMatrix"],
    ["goalRegressionEvidence", "goalRegression"],
    ["soakEvidence", "soak"],
  ];
  for (const [method] of stages)
    if (typeof driver[method] !== "function") throw new TypeError(`missing ${method}`);
  const report = {};
  for (const [method, field] of stages) report[field] = await driver[method]();
  report.contractVersion = INTERACTION_PLAN_CONTRACT_VERSION;
  if (scope === "functional") markInteractionPlanFunctionalReport(report);
  return validateInteractionPlanAcceptanceReport(report, { expectedSoakMinutes, scope });
}

/** 直接执行 focused runner；不自行启动旧 smoke，主控需传入已接入新合同的 desktop runner。 */
export async function main(argv = process.argv.slice(2)) {
  const options = parseInteractionPlanArguments(argv);
  const missing = await findMissingInteractionPlanHooks(repoRoot, options.desktopRunner);
  if (options.preflightOnly || missing.length > 0) {
    const verdict = missing.length === 0 ? "READY" : "NOT_VERIFIED";
    process.stdout.write(`JA_INTERACTION_PLAN_${verdict} missing=${JSON.stringify(missing)}\n`);
    return missing.length === 0 ? 0 : 2;
  }
  if (options.sidecarDirectory === undefined) throw new Error("--sidecar-directory is required");
  const sidecar = await readStagedInteractionPlanSidecar(options.sidecarDirectory);
  await mkdir(options.evidenceDirectory, { recursive: true });
  const environment = buildInteractionPlanDesktopEnvironment({
    evidenceDirectory: options.evidenceDirectory,
    sidecarManifest: sidecar.manifestPath,
    sidecarExecutable: sidecar.executable,
    soakMinutes: options.soakMinutes,
    scope: options.scope,
  });
  const { spawn } = await import("node:child_process");
  const exitCode = await new Promise((resolveExit, rejectExit) => {
    const child = spawn(process.execPath, [options.desktopRunner], {
      cwd: repoRoot,
      env: environment,
      stdio: "inherit",
      windowsHide: true,
    });
    child.once("error", rejectExit);
    child.once("exit", (code) => resolveExit(code ?? 1));
  });
  if (exitCode !== 0) throw new Error(`interaction-plan desktop runner failed code=${exitCode}`);
  const reportPath = environment.JA_E2E_INTERACTION_PLAN_REPORT;
  const report = JSON.parse(await readFile(reportPath, "utf8"));
  if (options.functionalOnly) markInteractionPlanFunctionalReport(report);
  validateInteractionPlanAcceptanceReport(report, {
    expectedSoakMinutes: options.soakMinutes,
    scope: options.scope,
  });
  if (options.functionalOnly) {
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  }
  const expectedVerificationStatus = options.functionalOnly
    ? "FUNCTIONAL_VERIFIED"
    : "VERIFIED";
  if (report.verificationStatus !== expectedVerificationStatus) {
    process.stdout.write(`JA_INTERACTION_PLAN_NOT_VERIFIED report=${reportPath}\n`);
    return 2;
  }
  process.stdout.write(`JA_INTERACTION_PLAN_PASS report=${reportPath}\n`);
  return 0;
}

if (process.argv[1] !== undefined && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.exitCode = await main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  });
}
