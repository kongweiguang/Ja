// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  PLAN_GOAL_CONTRACT_VERSION,
  PLAN_GOAL_UI_CONTRACT,
  buildPlanGoalDesktopEnvironment,
  collectPlanGoalAcceptanceReport,
  findMissingPlanGoalHooks,
  parsePlanGoalArguments,
  readStagedPlanGoalSidecar,
  validatePlanGoalAcceptanceReport,
} from "./plan-goal-production.mjs";
import {
  PlanGoalFixtureError,
  createPlanGoalStateFixture,
} from "./fixtures/plan-goal-state-fixture.mjs";

/** 构造覆盖全部生产维度的最小有效报告，供反例逐字段破坏。 */
function validReport() {
  const frames = [];
  for (const width of PLAN_GOAL_UI_CONTRACT.widths) {
    for (const scale of PLAN_GOAL_UI_CONTRACT.scales) {
      frames.push({
        width,
        scale,
        reducedMotion: width === 799 && scale === 1,
        reducedTransparency: width === 800 && scale === 1.25,
        longText: width === 800 && scale === 1.5,
        documentOverflow: 0,
        controlOverlapCount: 0,
        unnamedControlCount: 0,
        goalStatusLineCount: 1,
      });
    }
  }
  return {
    contractVersion: PLAN_GOAL_CONTRACT_VERSION,
    runtime: {
      surface: "tauri_webview2",
      nativeWindow: true,
      deterministicMockProvider: true,
      realProviderRequests: 0,
      nativeSidecar: {
        used: true,
        identityMatched: true,
        noFallback: true,
      },
    },
    authority: {
      planCreatedWithoutGoal: true,
      standalonePlanCompleted: true,
      planApprovalDidNotExecute: true,
      goalCreatedWithoutPlan: true,
      attachApprovedPlan: true,
      attachedGoalOwnedRunStarted: true,
      attachDidNotStartStandaloneRun: true,
      detachGoalStatus: "active",
      detachGoalPhase: "working",
      detachGoalRevisionAdvancedBy: 1,
      detachGoalContinued: true,
      staleGoalRevisionCode: "GOAL_REVISION_CONFLICT",
      stalePlanApprovalCode: "PLAN_APPROVAL_STALE",
      hiddenPlanDetailIoDelta: { planRead: 0, revisionList: 0, evidenceList: 0 },
    },
    composer: {
      slashGrouped: true,
      slashGroupLabel: "添加",
      slashCommands: ["plan", "goal"],
      slashDescriptions: {
        plan: "先制定计划再决定是否执行",
        goal: "设置要持续追求的目标",
      },
      keyboardKeys: ["ArrowDown", "ArrowUp", "Enter", "Escape"],
      enterExecutedSelection: true,
      escapeClosedMenu: true,
      focusReturnedToInput: true,
      planToggleOnChangedPlaceholder: true,
      planToggleOffRestoredPlaceholder: true,
      defaultPlaceholder: "随心输入",
      planPlaceholder: "描述需要制定计划的任务…",
      goalEditorLabel: "描述目标",
      goalEditorPlaceholder: "描述目标",
      goalObjectiveEditedInline: true,
      persistentSegmentedControlCount: 0,
      persistentPlanWorkbenchCount: 0,
      planDetailsOpenedExplicitly: true,
      planDetailsUnmountedAfterClose: true,
      explicitPlanDetailIoDelta: { planRead: 0, revisionList: 1, evidenceList: 1 },
      visibleModeIndicatorCount: 1,
      goalIndicatorOverridesPlan: true,
      goalStatusSingleLine: true,
    },
    visualMatrix: frames,
    evaluator: { verdicts: ["not_met", "met"], continuedAfterNotMet: true },
    soak: {
      requestedMinutes: 120,
      elapsedMs: 7_200_000,
      healthChecks: 240,
      healthy: true,
    },
    crashRecovery: {
      status: "passed",
      noBlindReplay: true,
      recoveredGoalStatus: "paused",
      recoveredGoalPhase: "needs_attention",
    },
  };
}

/** 断言 fixture 稳定错误码，不依赖错误正文。 */
function expectFixtureCode(action, code) {
  assert.throws(action, (error) => error instanceof PlanGoalFixtureError && error.code === code);
}

test("Plan 与 Goal 独立创建，只有显式 attach 才建立关联", () => {
  const fixture = createPlanGoalStateFixture();
  const standalonePlan = fixture.createPlan({ objective: "独立执行计划" });
  assert.deepEqual(fixture.inventory(), { plans: 1, goals: 0 });
  fixture.savePlanDraft(standalonePlan.planId, { steps: ["step_one"] });
  fixture.proposePlan(standalonePlan.planId);
  const standaloneApproved = fixture.approvePlan(standalonePlan.planId);
  assert.equal(standaloneApproved.activeRunId, null);
  const standaloneRunning = fixture.executePlan(standalonePlan.planId);
  assert.notEqual(standaloneRunning.activeRunId, null);
  const standaloneCompleted = fixture.completePlan(standalonePlan.planId);
  assert.equal(standaloneCompleted.status, "completed");
  assert.deepEqual(fixture.inventory(), { plans: 1, goals: 0 });

  const attachablePlan = fixture.createPlan({ objective: "只批准后附加" });
  fixture.savePlanDraft(attachablePlan.planId, { steps: ["step_attach"] });
  fixture.proposePlan(attachablePlan.planId);
  const approved = fixture.approvePlan(attachablePlan.planId);
  assert.equal(approved.activeRunId, null);

  const goal = fixture.createGoal({ objective: "独立持续推进目标" });
  assert.deepEqual(fixture.inventory(), { plans: 2, goals: 1 });
  assert.equal(goal.planLink, null);

  const attached = fixture.attachPlan({
    goalId: goal.goalId,
    expectedGoalRevision: goal.revision,
    planId: approved.planId,
    planRevisionId: approved.currentRevision.planRevisionId,
    planHash: approved.currentRevision.planHash,
  });
  assert.equal(attached.planLink.planId, approved.planId);
  assert.equal(attached.revision, goal.revision + 1);
  assert.match(attached.activeRunId, /^goal_plan_run_fixture_/u);
  assert.equal(fixture.readPlan(approved.planId).activeRunId, null);

  const detached = fixture.detachPlan({
    goalId: goal.goalId,
    expectedGoalRevision: attached.revision,
  });
  assert.equal(detached.planLink, null);
  assert.equal(detached.status, "active");
  assert.equal(detached.phase, "working");
  assert.equal(detached.revision, attached.revision + 1);
  assert.match(detached.activeRunId, /^goal_only_run_fixture_/u);
  assert.notEqual(detached.activeRunId, attached.activeRunId);
  assert.equal(fixture.readPlan(approved.planId).activeRunId, null);
});

test("attach/detach 拒绝过期 Goal CAS 与非当前批准 Plan identity", () => {
  const fixture = createPlanGoalStateFixture();
  const plan = fixture.createPlan({ objective: "versioned plan" });
  fixture.savePlanDraft(plan.planId, { steps: ["v1"] });
  const firstProposed = fixture.proposePlan(plan.planId);
  const first = fixture.approvePlan(plan.planId);
  const goal = fixture.createGoal({ objective: "versioned goal" });
  const attached = fixture.attachPlan({
    goalId: goal.goalId,
    expectedGoalRevision: 0,
    planId: first.planId,
    planRevisionId: first.currentRevision.planRevisionId,
    planHash: first.currentRevision.planHash,
  });
  expectFixtureCode(
    () => fixture.detachPlan({ goalId: goal.goalId, expectedGoalRevision: 0 }),
    "GOAL_REVISION_CONFLICT",
  );

  fixture.savePlanDraft(plan.planId, { steps: ["v1"] });
  fixture.proposePlan(plan.planId);
  const second = fixture.approvePlan(plan.planId);
  expectFixtureCode(
    () =>
      fixture.attachPlan({
        goalId: goal.goalId,
        expectedGoalRevision: attached.revision,
        planId: first.planId,
        planRevisionId: firstProposed.currentRevision.planRevisionId,
        planHash: firstProposed.currentRevision.planHash,
      }),
    "PLAN_APPROVAL_STALE",
  );
  assert.equal(second.currentRevision.revisionNumber, 2);
});

test("生产报告必须闭合真窗、键盘、视觉、evaluator 与恢复证据", () => {
  const report = validReport();
  assert.equal(validatePlanGoalAcceptanceReport(report), report);

  const staticOnly = structuredClone(report);
  staticOnly.runtime.surface = "source_contract";
  assert.throws(() => validatePlanGoalAcceptanceReport(staticOnly));

  const jarFallback = structuredClone(report);
  jarFallback.runtime.nativeSidecar.noFallback = false;
  assert.throws(() => validatePlanGoalAcceptanceReport(jarFallback));

  const missingScale = structuredClone(report);
  missingScale.visualMatrix = missingScale.visualMatrix.filter((frame) => frame.scale !== 1.5);
  assert.throws(() => validatePlanGoalAcceptanceReport(missingScale));

  const skippedNotMet = structuredClone(report);
  skippedNotMet.evaluator.verdicts = ["met"];
  assert.throws(() => validatePlanGoalAcceptanceReport(skippedNotMet));

  const persistentWorkbench = structuredClone(report);
  persistentWorkbench.composer.persistentPlanWorkbenchCount = 1;
  assert.throws(() => validatePlanGoalAcceptanceReport(persistentWorkbench));

  const hiddenDetailRead = structuredClone(report);
  hiddenDetailRead.authority.hiddenPlanDetailIoDelta.evidenceList = 1;
  assert.throws(() => validatePlanGoalAcceptanceReport(hiddenDetailRead));

  const unopenedDetails = structuredClone(report);
  unopenedDetails.composer.explicitPlanDetailIoDelta.revisionList = 0;
  assert.throws(() => validatePlanGoalAcceptanceReport(unopenedDetails));

  const stalePlanDescription = structuredClone(report);
  stalePlanDescription.composer.slashDescriptions.plan = "开启计划模式";
  assert.throws(() => validatePlanGoalAcceptanceReport(stalePlanDescription));
});

test("可注入 driver 必须按完整阶段编排并返回同一严格报告", async () => {
  const expected = validReport();
  const calls = [];
  const driver = Object.fromEntries(
    [
      ["runtimeEvidence", "runtime"],
      ["authorityEvidence", "authority"],
      ["composerEvidence", "composer"],
      ["visualEvidence", "visualMatrix"],
      ["evaluatorEvidence", "evaluator"],
      ["soakEvidence", "soak"],
      ["crashRecoveryEvidence", "crashRecovery"],
    ].map(([method, field]) => [
      method,
      async () => {
        calls.push(method);
        return structuredClone(expected[field]);
      },
    ]),
  );
  assert.deepEqual(await collectPlanGoalAcceptanceReport(driver), expected);
  assert.deepEqual(calls, [
    "runtimeEvidence",
    "authorityEvidence",
    "composerEvidence",
    "visualEvidence",
    "evaluatorEvidence",
    "soakEvidence",
    "crashRecoveryEvidence",
  ]);
  const shortExpected = structuredClone(expected);
  shortExpected.soak = { requestedMinutes: 0, elapsedMs: 0, healthChecks: 0, healthy: true };
  const shortDriver = {
    ...driver,
    soakEvidence: async () => structuredClone(shortExpected.soak),
  };
  assert.deepEqual(
    await collectPlanGoalAcceptanceReport(shortDriver, { expectedSoakMinutes: 0 }),
    shortExpected,
  );
  await assert.rejects(
    () => collectPlanGoalAcceptanceReport({ ...driver, visualEvidence: undefined }),
    /visualEvidence/u,
  );
});

test("focused 环境强制 mock Provider 并绑定 v1 报告路径", () => {
  const previousProvider = process.env.JA_REAL_PROVIDER_TOKEN;
  const previousE2eProvider = process.env.JA_E2E_REAL_PROVIDER_SECRET;
  process.env.JA_REAL_PROVIDER_TOKEN = "must-not-propagate";
  process.env.JA_E2E_REAL_PROVIDER_SECRET = "must-not-propagate";
  try {
    const environment = buildPlanGoalDesktopEnvironment({
      evidenceDirectory: "C:/evidence",
      sidecarManifest: "C:/sidecar/sidecar-manifest.json",
      sidecarExecutable: "C:/sidecar/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
    });
    assert.equal(environment.JA_E2E_PLAN_GOAL_ONLY, "1");
    assert.equal(environment.JA_E2E_PLAN_GOAL_CONTRACT_VERSION, "1");
    assert.match(environment.JA_E2E_PLAN_GOAL_REPORT, /plan-goal-report\.json$/u);
    assert.equal(environment.JA_E2E_PLAN_GOAL_SOAK_MINUTES, "120");
    assert.equal(environment.JA_E2E_PLAN_GOAL_SIDECAR_MANIFEST, "C:/sidecar/sidecar-manifest.json");
    assert.match(environment.JA_E2E_PLAN_GOAL_SIDECAR_EXECUTABLE, /ja-app-server-.+\.exe$/u);
    assert.equal(environment.JA_E2E_REAL_PROVIDER, "0");
    assert.equal(environment.JA_E2E_REAL_PROVIDER_API_KEY, "");
    assert.equal(environment.JA_E2E_APP_SERVER_JAR, "");
    assert.equal(environment.JA_REAL_PROVIDER_TOKEN, undefined);
    assert.equal(environment.JA_E2E_REAL_PROVIDER_SECRET, undefined);
  } finally {
    if (previousProvider === undefined) delete process.env.JA_REAL_PROVIDER_TOKEN;
    else process.env.JA_REAL_PROVIDER_TOKEN = previousProvider;
    if (previousE2eProvider === undefined) delete process.env.JA_E2E_REAL_PROVIDER_SECRET;
    else process.env.JA_E2E_REAL_PROVIDER_SECRET = previousE2eProvider;
  }
});

/**
 * 锁定 Native Responses strict schema 的必需占位字段，避免 fixture 在真实窗口中被协议层拒绝后
 * 只能等待全局期限；同时要求 standalone Plan 使用自己的 revision 生成幂等键。
 */
test("Plan Goal Provider fixture 满足严格 Tool 参数并区分 aggregate revision", async () => {
  const [runner, driver] = await Promise.all([
    readFile(new URL("./windows-desktop-smoke.mjs", import.meta.url), "utf8"),
    readFile(new URL("./plan-goal-webview2-driver.mjs", import.meta.url), "utf8"),
  ]);
  assert.match(runner, /failureSignature:\s*null/u);
  assert.match(runner, /planGoalAggregateRevision/u);
  assert.match(
    runner,
    /context\?\.kind === "goal" && context\?\.planRevisionId == null[\s\S]*?"goal_request_evaluation"/u,
  );
  assert.match(
    runner,
    /completedPlanGoalEvaluations === 0 &&[\s\S]*?planGoalContext\.planRevisionId == null[\s\S]*?criterionId: planGoalContext\.criterionId/u,
  );
  assert.match(runner, /trailingPlanGoalTool\?\.name === "goal_request_evaluation"/u);
  assert.doesNotMatch(runner, /planGoalSoakInputRequested\s*\?\s*1/u);
  assert.match(runner, /selectThreadById\(page, createdThreadId, deadline, signal\)/u);
  assert.match(driver, /attempt < 3/u);
  assert.match(driver, /result\.code !== "GOAL_REVISION_CONFLICT"/u);
  assert.match(driver, /idempotencyKey: createIdempotencyKey/u);
  assert.match(driver, /expectedGoalRevision: Number\.MAX_SAFE_INTEGER/u);
  assert.match(driver, /recordStage\("plan_goal_v1:soak_resume"\)/u);
  assert.match(driver, /recordStage\("plan_goal_v1:evaluator_met"\)/u);
});

test("preflight 明确拒绝旧强绑定 session，并接受独立 v1 hooks", async () => {
  const root = await mkdtemp(join(tmpdir(), "ja-plan-goal-contract-"));
  try {
    const appDir = join(root, "apps", "desktop", "src", "app", "composition");
    const apiDir = join(root, "apps", "desktop", "src", "api", "tauri");
    const goalApplicationDir = join(
      root,
      "apps",
      "desktop",
      "src",
      "features",
      "goals",
      "application",
    );
    const goalUiDir = join(root, "apps", "desktop", "src", "features", "goals", "ui");
    const workbenchUiDir = join(root, "apps", "desktop", "src", "features", "workbench", "ui");
    const preferencesDir = join(root, "apps", "desktop", "src", "shared", "preferences");
    const scriptDir = join(root, "scripts", "e2e");
    await mkdir(appDir, { recursive: true });
    await mkdir(apiDir, { recursive: true });
    await mkdir(goalApplicationDir, { recursive: true });
    await mkdir(goalUiDir, { recursive: true });
    await mkdir(workbenchUiDir, { recursive: true });
    await mkdir(preferencesDir, { recursive: true });
    await mkdir(scriptDir, { recursive: true });
    const desktopRunner = join(scriptDir, "windows-desktop-smoke.mjs");
    await writeFile(
      desktopRunner,
      "JA_E2E_PLAN_GOAL_CONTRACT_VERSION JA_E2E_PLAN_GOAL_REPORT " +
        "JA_E2E_PLAN_GOAL_SIDECAR_MANIFEST JA_E2E_PLAN_GOAL_SIDECAR_EXECUTABLE " +
        "runIndependentPlanGoalAcceptanceSession validatePlanGoalAcceptanceReport",
      "utf8",
    );
    await writeFile(
      join(appDir, "ConversationWorkspace.tsx"),
      'name: "plan"; name: "goal"; group: "添加"; ' +
        'description: "先制定计划再决定是否执行"; ' +
        'description: "设置要持续追求的目标"; GoalStatusBar PlanTimelineBlock ' +
        "onOpenDetails={onOpenGoal} 随心输入 " +
        "描述需要制定计划的任务… 描述目标",
      "utf8",
    );
    await writeFile(
      join(appDir, "WorkbenchHost.tsx"),
      "planGoalAvailable && goal !== undefined ? <PlanWorkbench /> : undefined",
      "utf8",
    );
    await writeFile(
      join(appDir, "JaApplication.tsx"),
      'detailsVisible: workbenchVisible && workbenchTab === "plan"',
      "utf8",
    );
    await writeFile(
      join(goalApplicationDir, "useGoalController.ts"),
      "detailsVisible; if (!detailsVisible) return",
      "utf8",
    );
    await writeFile(
      join(workbenchUiDir, "Workbench.tsx"),
      'openTabs.filter((tab) => tab.kind === "capability" && tab.capability !== "new") ' +
        "views[tab.capability]",
      "utf8",
    );
    await writeFile(
      join(preferencesDir, "uiPreferences.ts"),
      'const DEFAULT_RIGHT_PANEL_TABS: readonly RightPanelTab[] = ["review", "files", "preview"]; ' +
        'rightPanelTab: "files"',
      "utf8",
    );
    await writeFile(
      join(goalUiDir, "GoalStatusBar.tsx"),
      'data-goal-ui="status" data-phase aria-label="当前目标"',
      "utf8",
    );
    await writeFile(
      join(apiDir, "goals.ts"),
      "ja_runtime_plan_create ja_runtime_plan_read " +
        "ja_runtime_plan_draft_save ja_runtime_plan_propose " +
        "ja_runtime_plan_approve ja_runtime_plan_execute " +
        "ja_runtime_goal_plan_attach ja_runtime_goal_plan_detach",
      "utf8",
    );
    assert.deepEqual(await findMissingPlanGoalHooks(root, desktopRunner), []);

    await writeFile(
      desktopRunner,
      "JA_E2E_PLAN_GOAL_CONTRACT_VERSION JA_E2E_PLAN_GOAL_REPORT " +
        "JA_E2E_PLAN_GOAL_SIDECAR_MANIFEST JA_E2E_PLAN_GOAL_SIDECAR_EXECUTABLE " +
        "runIndependentPlanGoalAcceptanceSession validatePlanGoalAcceptanceReport " +
        "runPlanGoalAcceptanceSession",
      "utf8",
    );
    assert.deepEqual(await findMissingPlanGoalHooks(root, desktopRunner), [
      "desktop-v1-mode:retired:runPlanGoalAcceptanceSession",
    ]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("参数解析只接受显式证据目录与 desktop runner", () => {
  const parsed = parsePlanGoalArguments([
    "--evidence-directory",
    "C:/evidence",
    "--desktop-runner",
    "C:/runner.mjs",
    "--sidecar-directory",
    "C:/native-sidecar",
    "--soak-minutes",
    "0",
    "--preflight-only",
  ]);
  assert.equal(parsed.preflightOnly, true);
  assert.equal(parsed.soakMinutes, 0);
  assert.match(parsed.evidenceDirectory, /evidence$/u);
  assert.match(parsed.desktopRunner, /runner\.mjs$/u);
  assert.match(parsed.sidecarDirectory, /native-sidecar$/u);
  assert.throws(() => parsePlanGoalArguments([]), /evidence-directory/u);
  assert.throws(
    () => parsePlanGoalArguments(["--evidence-directory", "C:/evidence", "--unknown", "x"]),
    /unknown argument/u,
  );
  assert.throws(
    () => parsePlanGoalArguments(["--evidence-directory", "C:/evidence", "--soak-minutes", "1.5"]),
    /soak-minutes/u,
  );
});

test("Native Image staging manifest 必须与复制产物的大小和摘要一致", async () => {
  const root = await mkdtemp(join(tmpdir(), "ja-plan-goal-sidecar-"));
  try {
    const sidecars = join(root, "sidecars");
    await mkdir(sidecars, { recursive: true });
    const executable = join(sidecars, "ja-app-server-x86_64-pc-windows-msvc.exe");
    const content = Buffer.from("deterministic-native-image-fixture", "utf8");
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
    const staged = await readStagedPlanGoalSidecar(root);
    assert.equal(staged.executable, executable);
    assert.deepEqual(staged.identity, {
      used: true,
      identityMatched: true,
      noFallback: true,
      fileName: "ja-app-server-x86_64-pc-windows-msvc.exe",
      sha256,
      sizeBytes: content.length,
    });
    await writeFile(executable, Buffer.from("tampered", "utf8"));
    await assert.rejects(() => readStagedPlanGoalSidecar(root), /identity is incomplete/u);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
