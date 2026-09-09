// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Plan/Goal v1 的生产验证入口。Plan 与 Goal 是独立聚合；本 runner 只接受显式 attach/detach
 * 证据，不再把“创建 Goal 后编辑其内嵌 Plan”视为有效验收。
 */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, realpath, stat } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export const PLAN_GOAL_CONTRACT_VERSION = 1;

export const PLAN_GOAL_RPC_CONTRACT = Object.freeze({
  methods: Object.freeze({
    planCreate: "plan/create",
    planRead: "plan/read",
    planDraftSave: "plan/draft/save",
    planPropose: "plan/propose",
    planApprove: "plan/approve",
    planExecute: "plan/execute",
    goalCreate: "goal/create",
    goalRead: "goal/read",
    goalPause: "goal/pause",
    goalResume: "goal/resume",
    attach: "goal/plan/attach",
    detach: "goal/plan/detach",
  }),
  commands: Object.freeze({
    planCreate: "ja_runtime_plan_create",
    planRead: "ja_runtime_plan_read",
    planDraftSave: "ja_runtime_plan_draft_save",
    planPropose: "ja_runtime_plan_propose",
    planApprove: "ja_runtime_plan_approve",
    planExecute: "ja_runtime_plan_execute",
    goalCreate: "ja_runtime_goal_create",
    goalRead: "ja_runtime_goal_read",
    goalPause: "ja_runtime_goal_pause",
    goalResume: "ja_runtime_goal_resume",
    attach: "ja_runtime_goal_plan_attach",
    detach: "ja_runtime_goal_plan_detach",
  }),
  errors: Object.freeze({
    staleGoalRevision: "GOAL_REVISION_CONFLICT",
    stalePlanApproval: "PLAN_APPROVAL_STALE",
  }),
});

export const PLAN_GOAL_UI_CONTRACT = Object.freeze({
  slashGroupLabel: "添加",
  slashCommands: Object.freeze(["plan", "goal"]),
  slashDescriptions: Object.freeze({
    plan: "先制定计划再决定是否执行",
    goal: "设置要持续追求的目标",
  }),
  keys: Object.freeze(["ArrowUp", "ArrowDown", "Enter", "Escape"]),
  placeholders: Object.freeze({
    default: "随心输入",
    plan: "描述需要制定计划的任务…",
    goal: "描述目标",
  }),
  widths: Object.freeze([799, 800]),
  scales: Object.freeze([1, 1.25, 1.5]),
  forbiddenPersistentSurfaces: Object.freeze([
    '[data-goal-ui="collaboration-mode"]',
    '[data-goal-ui="plan-workbench"]',
  ]),
});

/** 只接受普通 JSON object，阻止数组或奇异原型绕过报告闭集。 */
function isObject(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

/** 把集合值排序后精确比较，既允许采集顺序差异，又拒绝遗漏或额外测试维度。 */
function sameSet(actual, expected) {
  return (
    Array.isArray(actual) &&
    JSON.stringify([...new Set(actual)].sort()) === JSON.stringify([...expected].sort())
  );
}

/**
 * 通过窄 driver 顺序采集独立聚合、Composer、视觉、evaluator 与恢复证据。真实 Playwright
 * driver 和确定性测试 fixture 共用此编排，因此任何一方遗漏阶段都会在报告校验前失败。
 */
export async function collectPlanGoalAcceptanceReport(driver, { expectedSoakMinutes = 120 } = {}) {
  if (!isObject(driver)) throw new TypeError("Plan/Goal acceptance driver is required");
  const required = [
    "runtimeEvidence",
    "authorityEvidence",
    "composerEvidence",
    "visualEvidence",
    "evaluatorEvidence",
    "soakEvidence",
    "crashRecoveryEvidence",
  ];
  for (const name of required) {
    if (typeof driver[name] !== "function") {
      throw new TypeError(`Plan/Goal acceptance driver is missing ${name}`);
    }
  }
  const report = {
    contractVersion: PLAN_GOAL_CONTRACT_VERSION,
    runtime: await driver.runtimeEvidence(),
    authority: await driver.authorityEvidence(),
    composer: await driver.composerEvidence(),
    visualMatrix: await driver.visualEvidence(),
    evaluator: await driver.evaluatorEvidence(),
    soak: await driver.soakEvidence(),
    crashRecovery: await driver.crashRecoveryEvidence(),
  };
  return validatePlanGoalAcceptanceReport(report, { expectedSoakMinutes });
}

/**
 * 校验 Plan/Goal v1 真窗报告。报告必须来自真实 Tauri/WebView2 且使用确定性 mock Provider；
 * source/static preflight 不能构造此报告，也不能通过本函数取得 production PASS。
 */
export function validatePlanGoalAcceptanceReport(report, { expectedSoakMinutes = 120 } = {}) {
  assert.equal(isObject(report), true, "Plan/Goal report must be an object");
  assert.equal(report.contractVersion, PLAN_GOAL_CONTRACT_VERSION);
  assert.equal(report.runtime?.surface, "tauri_webview2");
  assert.equal(report.runtime?.nativeWindow, true);
  assert.equal(report.runtime?.deterministicMockProvider, true);
  assert.equal(report.runtime?.realProviderRequests, 0);
  assert.equal(report.runtime?.nativeSidecar?.used, true);
  assert.equal(report.runtime?.nativeSidecar?.identityMatched, true);
  assert.equal(report.runtime?.nativeSidecar?.noFallback, true);

  const authority = report.authority;
  assert.equal(isObject(authority), true);
  assert.equal(authority.planCreatedWithoutGoal, true);
  assert.equal(authority.standalonePlanCompleted, true);
  assert.equal(authority.planApprovalDidNotExecute, true);
  assert.equal(authority.goalCreatedWithoutPlan, true);
  assert.equal(authority.attachApprovedPlan, true);
  assert.equal(authority.attachedGoalOwnedRunStarted, true);
  assert.equal(authority.attachDidNotStartStandaloneRun, true);
  assert.equal(authority.detachGoalStatus, "active");
  assert.equal(authority.detachGoalPhase, "working");
  assert.equal(authority.detachGoalRevisionAdvancedBy, 1);
  assert.equal(authority.detachGoalContinued, true);
  assert.equal(authority.staleGoalRevisionCode, "GOAL_REVISION_CONFLICT");
  assert.equal(authority.stalePlanApprovalCode, "PLAN_APPROVAL_STALE");
  assert.deepEqual(authority.hiddenPlanDetailIoDelta, {
    planRead: 0,
    revisionList: 0,
    evidenceList: 0,
  });

  const composer = report.composer;
  assert.equal(isObject(composer), true);
  assert.equal(composer.slashGrouped, true);
  assert.equal(composer.slashGroupLabel, PLAN_GOAL_UI_CONTRACT.slashGroupLabel);
  assert.equal(sameSet(composer.slashCommands, PLAN_GOAL_UI_CONTRACT.slashCommands), true);
  assert.deepEqual(composer.slashDescriptions, PLAN_GOAL_UI_CONTRACT.slashDescriptions);
  assert.equal(sameSet(composer.keyboardKeys, PLAN_GOAL_UI_CONTRACT.keys), true);
  assert.equal(composer.enterExecutedSelection, true);
  assert.equal(composer.escapeClosedMenu, true);
  assert.equal(composer.focusReturnedToInput, true);
  assert.equal(composer.planToggleOnChangedPlaceholder, true);
  assert.equal(composer.planToggleOffRestoredPlaceholder, true);
  assert.equal(composer.defaultPlaceholder, PLAN_GOAL_UI_CONTRACT.placeholders.default);
  assert.equal(composer.planPlaceholder, PLAN_GOAL_UI_CONTRACT.placeholders.plan);
  assert.equal(composer.goalEditorLabel, PLAN_GOAL_UI_CONTRACT.placeholders.goal);
  assert.equal(composer.goalEditorPlaceholder, PLAN_GOAL_UI_CONTRACT.placeholders.goal);
  assert.equal(composer.goalObjectiveEditedInline, true);
  assert.equal(composer.persistentSegmentedControlCount, 0);
  assert.equal(composer.persistentPlanWorkbenchCount, 0);
  assert.equal(composer.planDetailsOpenedExplicitly, true);
  assert.equal(composer.planDetailsUnmountedAfterClose, true);
  assert.equal(Number.isSafeInteger(composer.explicitPlanDetailIoDelta?.planRead), true);
  assert.equal(composer.explicitPlanDetailIoDelta.planRead >= 0, true);
  assert.equal(composer.explicitPlanDetailIoDelta?.revisionList > 0, true);
  assert.equal(composer.explicitPlanDetailIoDelta?.evidenceList > 0, true);
  assert.equal(composer.visibleModeIndicatorCount, 1);
  assert.equal(composer.goalIndicatorOverridesPlan, true);
  assert.equal(composer.goalStatusSingleLine, true);

  const frames = report.visualMatrix;
  assert.equal(Array.isArray(frames) && frames.length >= 6, true);
  assert.equal(
    sameSet(
      frames.map((frame) => frame.width),
      PLAN_GOAL_UI_CONTRACT.widths,
    ),
    true,
  );
  assert.equal(
    sameSet(
      frames.map((frame) => frame.scale),
      PLAN_GOAL_UI_CONTRACT.scales,
    ),
    true,
  );
  for (const width of PLAN_GOAL_UI_CONTRACT.widths) {
    for (const scale of PLAN_GOAL_UI_CONTRACT.scales) {
      assert.equal(
        frames.some((frame) => frame.width === width && frame.scale === scale),
        true,
        `missing visual frame ${width}@${scale}`,
      );
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
    frames.some((frame) => frame.longText === true),
    true,
  );
  for (const frame of frames) {
    assert.equal(frame.documentOverflow, 0);
    assert.equal(frame.controlOverlapCount, 0);
    assert.equal(frame.unnamedControlCount, 0);
    assert.equal(frame.goalStatusLineCount, 1);
  }

  assert.deepEqual(report.evaluator?.verdicts, ["not_met", "met"]);
  assert.equal(report.evaluator?.continuedAfterNotMet, true);
  assert.equal(report.soak?.requestedMinutes, expectedSoakMinutes);
  assert.equal(report.soak?.elapsedMs >= expectedSoakMinutes * 60_000, true);
  assert.equal(report.soak?.healthChecks >= expectedSoakMinutes * 2, true);
  assert.equal(report.soak?.healthy, true);
  assert.deepEqual(report.crashRecovery, {
    status: "passed",
    noBlindReplay: true,
    recoveredGoalStatus: "paused",
    recoveredGoalPhase: "needs_attention",
  });
  return report;
}

/**
 * 构造 focused desktop 环境。它清空所有真实 Provider 入口，并要求 smoke 将 v1 报告写到
 * 显式路径；通用 smoke 或旧 plan_goal_loopback 不能满足该合同。
 */
export function buildPlanGoalDesktopEnvironment({
  evidenceDirectory,
  sidecarManifest,
  sidecarExecutable,
  soakMinutes = 120,
}) {
  const environment = {
    ...process.env,
    JA_E2E_PLAN_GOAL_ONLY: "1",
    JA_E2E_PLAN_GOAL_CONTRACT_VERSION: String(PLAN_GOAL_CONTRACT_VERSION),
    JA_E2E_PLAN_GOAL_REPORT: join(evidenceDirectory, "plan-goal-report.json"),
    JA_E2E_PLAN_GOAL_SOAK_MINUTES: String(soakMinutes),
    JA_E2E_PLAN_GOAL_SIDECAR_MANIFEST: sidecarManifest,
    JA_E2E_PLAN_GOAL_SIDECAR_EXECUTABLE: sidecarExecutable,
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

/**
 * 检查新 runner 的最小接线；缺失项返回机器可读标签，供主线尚未完成时报告 NOT VERIFIED。
 */
export async function findMissingPlanGoalHooks(root = repoRoot, desktopRunner) {
  const checks = [
    {
      label: "desktop-v1-mode",
      path: desktopRunner ?? join(root, "scripts", "e2e", "windows-desktop-smoke.mjs"),
      tokens: [
        "JA_E2E_PLAN_GOAL_CONTRACT_VERSION",
        "JA_E2E_PLAN_GOAL_REPORT",
        "JA_E2E_PLAN_GOAL_SIDECAR_MANIFEST",
        "JA_E2E_PLAN_GOAL_SIDECAR_EXECUTABLE",
        "runIndependentPlanGoalAcceptanceSession",
        "validatePlanGoalAcceptanceReport",
      ],
      forbidden: ["runPlanGoalAcceptanceSession"],
    },
    {
      label: "composer-slash-plan-goal",
      path: join(root, "apps", "desktop", "src", "app", "composition", "ConversationWorkspace.tsx"),
      tokens: [
        'name: "plan"',
        'name: "goal"',
        'group: "添加"',
        'description: "先制定计划再决定是否执行"',
        'description: "设置要持续追求的目标"',
      ],
    },
    {
      label: "composer-inline-contract",
      path: join(root, "apps", "desktop", "src", "app", "composition", "ConversationWorkspace.tsx"),
      tokens: ["随心输入", "描述需要制定计划的任务…", "描述目标"],
    },
    {
      label: "conversation-explicit-plan-details",
      path: join(root, "apps", "desktop", "src", "app", "composition", "ConversationWorkspace.tsx"),
      tokens: ["PlanTimelineBlock", "onOpenDetails={onOpenGoal}"],
      forbidden: ["CollaborationModeControl"],
    },
    {
      label: "workbench-conditional-plan-capability",
      path: join(root, "apps", "desktop", "src", "app", "composition", "WorkbenchHost.tsx"),
      tokens: ["planGoalAvailable && goal !== undefined", "<PlanWorkbench"],
    },
    {
      label: "workbench-open-tabs-only",
      path: join(root, "apps", "desktop", "src", "features", "workbench", "ui", "Workbench.tsx"),
      tokens: [
        "openTabs",
        'tab.kind === "capability" && tab.capability !== "new"',
        "views[tab.capability",
      ],
    },
    {
      label: "workbench-default-plan-closed",
      path: join(root, "apps", "desktop", "src", "shared", "preferences", "uiPreferences.ts"),
      tokens: [
        'const DEFAULT_RIGHT_PANEL_TABS: readonly RightPanelTab[] = ["review", "files", "preview"]',
        'rightPanelTab: "files"',
      ],
    },
    {
      label: "goal-detail-io-gate",
      path: join(
        root,
        "apps",
        "desktop",
        "src",
        "features",
        "goals",
        "application",
        "useGoalController.ts",
      ),
      tokens: ["detailsVisible", "if (!detailsVisible)"],
    },
    {
      label: "goal-detail-visibility-wiring",
      path: join(root, "apps", "desktop", "src", "app", "composition", "JaApplication.tsx"),
      tokens: ["detailsVisible:", 'workbenchVisible && workbenchTab === "plan"'],
    },
    {
      label: "goal-single-line-status",
      path: join(root, "apps", "desktop", "src", "features", "goals", "ui", "GoalStatusBar.tsx"),
      tokens: ['data-goal-ui="status"', "data-phase", 'aria-label="当前目标"'],
    },
    {
      label: "goal-plan-commands",
      path: join(root, "apps", "desktop", "src", "api", "tauri", "goals.ts"),
      tokens: [
        "ja_runtime_plan_create",
        "ja_runtime_plan_read",
        "ja_runtime_plan_draft_save",
        "ja_runtime_plan_propose",
        "ja_runtime_plan_approve",
        "ja_runtime_plan_execute",
        "ja_runtime_goal_plan_attach",
        "ja_runtime_goal_plan_detach",
      ],
    },
  ];
  const missing = [];
  for (const check of checks) {
    let source;
    try {
      source = await readFile(check.path, "utf8");
    } catch {
      missing.push(`${check.label}:file`);
      continue;
    }
    for (const token of check.tokens) {
      if (!source.includes(token)) missing.push(`${check.label}:${token}`);
    }
    for (const token of check.forbidden ?? []) {
      if (source.includes(token)) missing.push(`${check.label}:retired:${token}`);
    }
  }
  return missing;
}

/** 解析生产 runner 的显式参数；证据目录必填，避免报告散落到源码树。 */
export function parsePlanGoalArguments(argv) {
  const parsed = {
    evidenceDirectory: undefined,
    sidecarDirectory: undefined,
    desktopRunner: join(repoRoot, "scripts", "e2e", "windows-desktop-smoke.mjs"),
    soakMinutes: 120,
    preflightOnly: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--preflight-only") {
      parsed.preflightOnly = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`missing value for ${argument}`);
    }
    if (argument === "--evidence-directory") parsed.evidenceDirectory = resolve(value);
    else if (argument === "--sidecar-directory") parsed.sidecarDirectory = resolve(value);
    else if (argument === "--desktop-runner") parsed.desktopRunner = resolve(value);
    else if (argument === "--soak-minutes") {
      parsed.soakMinutes = Number(value);
      if (
        !Number.isSafeInteger(parsed.soakMinutes) ||
        parsed.soakMinutes < 0 ||
        parsed.soakMinutes > 1_440
      ) {
        throw new Error("--soak-minutes must be an integer from 0 to 1440");
      }
    } else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (parsed.evidenceDirectory === undefined) {
    throw new Error("--evidence-directory is required");
  }
  return parsed;
}

/** 以流式摘要绑定 staged executable，避免 Native Image 整体进入 Node heap。 */
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

/**
 * 生产 Plan/Goal runner 只接受 staging manifest 证明的复制产物；独立保留此窄校验，避免
 * 与其它 focused runner 形成隐式启动依赖，同时拒绝 JAR 或同名旧 sidecar 回退。
 */
export async function readStagedPlanGoalSidecar(sidecarDirectory) {
  const manifestPath = join(sidecarDirectory, "sidecar-manifest.json");
  let manifest;
  try {
    manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  } catch {
    throw new Error("staged sidecar manifest is missing or malformed");
  }
  const source = manifest?.sidecar?.sourceArtifact;
  const staged = manifest?.sidecar?.stagedArtifact;
  const relativePath = manifest?.sidecar?.relativePath;
  const expectedRelativePath = "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe";
  if (
    manifest?.product !== "Ja" ||
    manifest?.nativeImageOnly !== true ||
    manifest?.noFallback !== true ||
    manifest?.stagingMode !== "copy" ||
    relativePath !== expectedRelativePath ||
    source?.sha256 !== staged?.sha256 ||
    source?.sizeBytes !== staged?.sizeBytes
  ) {
    throw new Error("staged manifest does not prove a copied no-fallback Ja Native Image");
  }
  const [resolvedDirectory, executable] = await Promise.all([
    realpath(sidecarDirectory),
    realpath(resolve(sidecarDirectory, ...relativePath.split("/"))),
  ]);
  const containmentPath = relative(resolvedDirectory, executable);
  if (containmentPath.startsWith("..") || isAbsolute(containmentPath)) {
    throw new Error("staged sidecar path escaped its evidence directory");
  }
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

/** 启动唯一 desktop runner，并保留参数数组边界，避免路径经过字符串 shell 重解释。 */
function runDesktop(desktopRunner, environment) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(process.execPath, [desktopRunner], {
      cwd: repoRoot,
      env: environment,
      stdio: "inherit",
      windowsHide: true,
    });
    child.once("error", rejectRun);
    child.once("exit", (code, signal) => resolveRun({ code, signal }));
  });
}

/**
 * 执行 preflight 或真窗验收。preflight 缺 hook 时返回 NOT VERIFIED；只有报告经过严格校验
 * 且 desktop 子进程成功，才输出 PASS。
 */
export async function main(argv = process.argv.slice(2)) {
  const options = parsePlanGoalArguments(argv);
  const missing = await findMissingPlanGoalHooks(repoRoot, options.desktopRunner);
  if (options.preflightOnly || missing.length > 0) {
    const verdict = missing.length === 0 ? "READY" : "NOT_VERIFIED";
    process.stdout.write(`JA_PLAN_GOAL_${verdict} missing=${JSON.stringify(missing)}\n`);
    return missing.length === 0 ? 0 : 2;
  }
  if (options.sidecarDirectory === undefined) {
    throw new Error("--sidecar-directory is required for production acceptance");
  }
  const sidecar = await readStagedPlanGoalSidecar(options.sidecarDirectory);
  await mkdir(options.evidenceDirectory, { recursive: true });
  const environment = buildPlanGoalDesktopEnvironment({
    ...options,
    sidecarManifest: sidecar.manifestPath,
    sidecarExecutable: sidecar.executable,
  });
  const outcome = await runDesktop(options.desktopRunner, environment);
  if (outcome.code !== 0) {
    throw new Error(
      `Plan/Goal desktop runner failed: code=${String(outcome.code)} signal=${String(outcome.signal)}`,
    );
  }
  const reportPath = environment.JA_E2E_PLAN_GOAL_REPORT;
  const report = JSON.parse(await readFile(reportPath, "utf8"));
  validatePlanGoalAcceptanceReport(report, { expectedSoakMinutes: options.soakMinutes });
  process.stdout.write(`JA_PLAN_GOAL_PASS report=${reportPath}\n`);
  return 0;
}

/** 仅在直接执行时设置退出码；测试 import 不启动真窗或访问 Provider。 */
function isDirectExecution() {
  return process.argv[1] !== undefined && pathToFileURL(process.argv[1]).href === import.meta.url;
}

if (isDirectExecution()) {
  process.exitCode = await main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  });
}
