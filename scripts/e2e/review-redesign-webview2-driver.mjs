// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Review redesign 的 WebView2 驱动层。调用方必须提供已连接到真实 Tauri main WebView 的
 * Playwright Page；本模块只经可见控件和真实 Tauri invoke adapter 验收，不替换 Git 命令。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const REVIEW_SHELL = "[data-ja-review-shell]";
const REVIEW_TREE = "[data-ja-review-tree]";
const REVIEW_FILE = "[data-review-file-id][data-review-layer]";
const REVIEW_GROUP = "[data-review-group]";
const STEP_TIMEOUT_MS = 30_000;
const THREAD_READY_TIMEOUT_MS = 30_000;
const REVIEW_NARROW_CONTENT_WIDTH = 760;
const REVIEW_WIDTH_TOLERANCE = 2;
const REVIEW_DEVICE_PIXEL_RATIOS = Object.freeze([1, 1.25, 1.5]);
export const REVIEW_DRIVER_STAGES = Object.freeze([
  "driver:initialize",
  "driver:reload",
  "driver:runtime_readiness",
  "driver:project_select",
  "driver:thread_prepare",
  "driver:thread_surface_ready",
  "driver:review_open",
  "driver:source_catalog_menu",
  "driver:catalog_ready_callback",
  "driver:git_interactions",
  "driver:responsive_matrix",
  "driver:after_git_callback",
  "driver:report_validation",
]);
const REVIEW_DRIVER_STAGE_SET = new Set(REVIEW_DRIVER_STAGES);

/**
 * @author kongweiguang
 * 只用真实侧栏选择与当前 Thread Workbench scope 证明会话身份；空 Thread 不挂 Timeline，不能拿它当 Review 前置条件。
 */
export function readReviewThreadSurfaceState({ includeUnready = false } = {}) {
  const history = globalThis.document.querySelector('[aria-label="最近对话列表"]');
  const selected = history?.querySelector('button[data-thread-id][aria-current="page"]') ?? null;
  const threadId = selected?.getAttribute("data-thread-id") ?? null;
  const threadIdValid =
    typeof threadId === "string" && /^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(threadId);
  const visibleSessions = [
    ...globalThis.document.querySelectorAll(
      ".ja-thread-workbench-session:not([hidden])[data-thread-workbench-scope]",
    ),
  ];
  const matchingSessionCount = threadIdValid
    ? visibleSessions.filter((session) =>
        session.getAttribute("data-thread-workbench-scope")?.endsWith(`:${threadId}`),
      ).length
    : 0;
  const conversationState = globalThis.document
    .querySelector(".ja-conversation-body")
    ?.getAttribute("data-state");
  const timelineMountedForSelectedThread =
    threadIdValid &&
    [...globalThis.document.querySelectorAll(".ja-chat-timeline[data-thread-id]")].some(
      (timeline) => timeline.getAttribute("data-thread-id") === threadId,
    );
  const ready = threadIdValid && matchingSessionCount === 1;
  if (!ready && !includeUnready) return false;
  return {
    ready,
    historyListPresent: history !== null,
    historyBusy: history?.getAttribute("aria-busy") === "true",
    selectedThreadPresent: selected !== null,
    selectedThreadIdValid: threadIdValid,
    visibleWorkbenchSessionCount: Math.min(visibleSessions.length, 8),
    workbenchSessionMatchesSelectedThread: matchingSessionCount === 1,
    conversationBodyState:
      conversationState === "empty" || conversationState === "content"
        ? conversationState
        : "unknown",
    timelineMountedForSelectedThread,
  };
}

/**
 * @author kongweiguang
 * 失败证据只投影闭集布尔值/计数和相对截图名，防止 Thread ID、标题、正文、URL 或错误原文进入报告。
 */
export function sanitizeReviewThreadFailureDiagnostic(candidate) {
  if (candidate === null || typeof candidate !== "object") return undefined;
  const stage = candidate.stage;
  const code = candidate.code;
  if (!REVIEW_DRIVER_STAGE_SET.has(stage)) return undefined;
  if (code !== "THREAD_SURFACE_NOT_READY" && code !== "THREAD_CREATION_ACTION_FAILED")
    return undefined;
  const state = candidate.surface;
  const safeState = state !== null && typeof state === "object" ? state : {};
  const bodyState = safeState.conversationBodyState;
  const screenshotCaptured = candidate.screenshotCaptured === true;
  return {
    code,
    stage,
    surface: {
      ready: safeState.ready === true,
      historyListPresent: safeState.historyListPresent === true,
      historyBusy: safeState.historyBusy === true,
      selectedThreadPresent: safeState.selectedThreadPresent === true,
      selectedThreadIdValid: safeState.selectedThreadIdValid === true,
      visibleWorkbenchSessionCount:
        Number.isSafeInteger(safeState.visibleWorkbenchSessionCount) &&
        safeState.visibleWorkbenchSessionCount >= 0
          ? Math.min(safeState.visibleWorkbenchSessionCount, 8)
          : 0,
      workbenchSessionMatchesSelectedThread:
        safeState.workbenchSessionMatchesSelectedThread === true,
      conversationBodyState:
        bodyState === "empty" || bodyState === "content" ? bodyState : "unknown",
      timelineMountedForSelectedThread: safeState.timelineMountedForSelectedThread === true,
    },
    screenshotCaptured,
    ...(screenshotCaptured ? { screenshot: "screenshots/thread-readiness-failure.png" } : {}),
  };
}

/**
 * @author kongweiguang
 * Review 页面读取 Workspace diff，不代表 TurnReview 生命周期完成；只有显式通过其完整 Gate 才能产生 full PASS。
 */
export function reviewRedesignVerdict(scope, turnReviewStatus) {
  return scope === "full" && turnReviewStatus === "passed" ? "PASS" : "NOT VERIFIED";
}

/** 给 full WebView2 验收持有首次真实 Catalog 调用，以验证打开中的来源菜单有加载反馈。 */
function installReviewCatalogTestGate() {
  globalThis.__JA_REVIEW_CATALOG_TEST_GATE__ = {
    armed: true,
    entered: false,
    released: false,
    rejectFirst: true,
    failureInjected: false,
  };
}

/** CDP 会以浮点误差回读缩放倍率；仅归一化支持矩阵内 1e-6 以内的读数，避免放宽验收范围。 */
export function normalizeDevicePixelRatio(value) {
  assert.ok(Number.isFinite(value), "device pixel ratio must be finite");
  const normalized = REVIEW_DEVICE_PIXEL_RATIOS.find((ratio) => Math.abs(value - ratio) <= 1e-6);
  assert.ok(normalized !== undefined, "device pixel ratio is unsupported");
  return normalized;
}

/** 同来源选层可复用当前 Workspace 的 settled snapshot；切换来源必须拒绝选择前的旧调用。 */
export function findSettledUncommittedReviewSnapshot({ startIndex, requiresFresh }) {
  const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
  const workspaceId = calls
    .filter((call) => call.command === "ja_review_catalog" && call.phase === "resolved")
    .at(-1)?.workspaceId;
  const snapshots = calls
    .map((call, index) => ({ call, index }))
    .filter(
      ({ call }) =>
        call.command === "ja_review_snapshot" &&
        call.source?.kind === "uncommitted" &&
        typeof workspaceId === "string" &&
        call.workspaceId === workspaceId,
    );
  const pending = calls.some(
    (call) =>
      ["ja_review_catalog", "ja_review_snapshot"].includes(call.command) &&
      call.phase === "started",
  );
  const candidates = requiresFresh
    ? snapshots.filter(({ index }) => index >= startIndex).map(({ call }) => call)
    : snapshots.map(({ call }) => call);
  const snapshot = candidates.at(-1);
  globalThis.__JA_REVIEW_SCOPE_SELECTION_WAIT__ = {
    startIndex,
    requiresFresh,
    candidateCount: candidates.length,
    pending,
    latestPhase: snapshot?.phase ?? null,
    workspaceMatched: typeof workspaceId === "string" && snapshot?.workspaceId === workspaceId,
  };
  return pending || snapshot === undefined || snapshot.phase === "started" ? false : snapshot;
}

/**
 * 以 4 个容器宽度交叉 3 个主题；DPI 与 reduced motion 均匀轮换，明确形成 12 帧代表矩阵，
 * 不把它描述成 72 帧的全笛卡尔积。
 */
export const REVIEW_REDESIGN_MATRIX = Object.freeze(
  [360, 520, 760, 1000].flatMap((width, widthIndex) =>
    ["light", "dark", "system"].map((theme, themeIndex) =>
      Object.freeze({
        width,
        viewportWidth: width <= 520 ? 1280 : width === 760 ? 1600 : 2200,
        viewportHeight: 900,
        theme,
        systemColorScheme: theme === "system" ? "dark" : theme,
        devicePixelRatio: [1, 1.25, 1.5][(widthIndex + themeIndex) % 3],
        reducedMotion: (widthIndex + themeIndex) % 2 === 1,
      }),
    ),
  ),
);

/** AI finding 面板的窄容器矩阵完整交叉三种外观模式与三种 CDP 密度。 */
export const CODING_REVIEW_AI_NARROW_MATRIX = Object.freeze(
  ["light", "dark", "system"].flatMap((theme) =>
    [1, 1.25, 1.5].map((devicePixelRatio) =>
      Object.freeze({
        width: 360,
        viewportWidth: 1280,
        viewportHeight: 900,
        theme,
        systemColorScheme: theme === "system" ? "dark" : theme,
        devicePixelRatio,
        reducedMotion: false,
      }),
    ),
  ),
);

/** Palette values are selected through Settings; every theme/palette pair is compared wide and narrow. */
export const CODING_REVIEW_AI_PALETTES = Object.freeze([
  Object.freeze({ value: "ja", label: "Ja" }),
  Object.freeze({ value: "jetbrains", label: "JetBrains" }),
  Object.freeze({ value: "xcode", label: "Xcode" }),
  Object.freeze({ value: "obsidian", label: "Obsidian" }),
  Object.freeze({ value: "claude", label: "Claude" }),
]);

/** A complete theme/palette cross-product prevents palette contrast from being inferred from one mode. */
export const CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX = Object.freeze(
  ["light", "dark", "system"].flatMap((theme) =>
    CODING_REVIEW_AI_PALETTES.flatMap(({ value: palette, label: paletteLabel }) =>
      [
        { width: 760, layout: "wide" },
        { width: 360, layout: "narrow" },
      ].map(({ width, layout }) =>
        Object.freeze({
          theme,
          systemColorScheme: theme === "system" ? "dark" : theme,
          palette,
          paletteLabel,
          width,
          layout,
          reducedMotion: false,
        }),
      ),
    ),
  ),
);

/** Wait for a Native result tied to the selected source, file, snapshot revision, and a non-loading UI. */
export function readCodingReviewDiffReadiness({ scopeLabel: expectedScopeLabel, source }) {
  const isVisible = (element) => {
    if (element === null) return false;
    const style = globalThis.getComputedStyle(element);
    return (
      style.display !== "none" &&
      style.visibility !== "hidden" &&
      element.getClientRects().length > 0
    );
  };
  const session = globalThis.document.querySelector(".ja-thread-workbench-session:not([hidden])");
  const scopeTrigger = session?.querySelector(".ja-review-source-trigger");
  if (!isVisible(scopeTrigger) || scopeTrigger.getAttribute("aria-label") !== expectedScopeLabel)
    return false;

  const shell = session.querySelector(
    '.ja-inspector[aria-label="工作区面板"] .ja-workbench [data-ja-review-shell]',
  );
  const diff = shell?.querySelector(".ja-review-diff");
  const fileId = diff?.getAttribute("data-review-selected-file-id");
  const layer = diff?.getAttribute("data-review-selected-layer");
  if (typeof fileId !== "string" || fileId.length === 0 || typeof layer !== "string") return false;

  const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
  const workspaceId = calls
    .filter((call) => call.command === "ja_review_catalog" && call.phase === "resolved")
    .at(-1)?.workspaceId;
  if (typeof workspaceId !== "string" || workspaceId.length === 0) return false;

  const sameSource = (candidate) => JSON.stringify(candidate) === JSON.stringify(source);
  const snapshots = calls.filter(
    (call) =>
      call.command === "ja_review_snapshot" &&
      call.phase === "resolved" &&
      call.workspaceId === workspaceId &&
      sameSource(call.source),
  );
  const snapshotPending = calls.some(
    (call) =>
      call.command === "ja_review_snapshot" &&
      call.phase === "started" &&
      call.workspaceId === workspaceId &&
      sameSource(call.source),
  );
  const snapshot = snapshots.at(-1);
  if (
    snapshotPending ||
    snapshot === undefined ||
    typeof snapshot.resultRevision !== "string" ||
    snapshot.resultRevision.length === 0
  )
    return false;

  const matchingDiffCalls = calls.filter(
    (call) =>
      call.command === "ja_review_file_diff" &&
      call.workspaceId === workspaceId &&
      sameSource(call.source) &&
      call.revision === snapshot.resultRevision &&
      call.fileId === fileId &&
      (call.phase === "started" || call.phase === "rejected" || call.resultLayer === layer),
  );
  const diffCall = matchingDiffCalls.at(-1);
  if (diffCall?.phase === "started") return false;

  const visibleStatusCount = [...(diff?.querySelectorAll('[role="status"]') ?? [])].filter(
    isVisible,
  ).length;
  const visibleErrorCount = [...(diff?.querySelectorAll(".ja-review-empty.is-error") ?? [])].filter(
    isVisible,
  ).length;
  const diffHeaderVisible = isVisible(diff?.querySelector(".ja-review-diff-header") ?? null);
  if (diffCall?.phase === "rejected" || visibleErrorCount > 0) {
    return {
      state: "error",
      scopeLabel: expectedScopeLabel,
      source,
      workspaceId,
      snapshotRevision: snapshot.resultRevision,
      fileId,
      layer,
      callPhase: diffCall?.phase ?? null,
      errorCode: diffCall?.errorCode ?? null,
      visibleErrorCount,
      visibleStatusCount,
      diffHeaderVisible,
    };
  }
  if (
    diffCall?.phase !== "resolved" ||
    diffCall.resultFileId !== fileId ||
    diffCall.resultLayer !== layer ||
    diffCall.resultRevision !== snapshot.resultRevision ||
    visibleStatusCount > 0 ||
    !diffHeaderVisible
  )
    return false;

  return {
    state: "ready",
    scopeLabel: expectedScopeLabel,
    source,
    workspaceId,
    snapshotRevision: snapshot.resultRevision,
    fileId,
    layer,
    revision: diffCall.revision,
    resultFileId: diffCall.resultFileId,
    resultLayer: diffCall.resultLayer,
    resultRevision: diffCall.resultRevision,
    callPhase: diffCall.phase,
    loading: visibleStatusCount > 0,
    visibleErrorCount,
    diffHeaderVisible,
  };
}

/** Reject frame evidence unless the Native result and visible Diff share the expected full identity. */
export function validateCodingReviewDiffReadiness(
  evidence,
  expected = { scopeLabel: "审阅范围：未提交", source: { kind: "uncommitted" } },
) {
  assert.equal(
    evidence?.state,
    "ready",
    `AI Review matrix Diff is not ready: ${JSON.stringify(evidence)}`,
  );
  assert.equal(evidence.scopeLabel, expected.scopeLabel);
  assert.deepEqual(evidence.source, expected.source);
  assert.equal(typeof evidence.workspaceId, "string");
  assert.ok(evidence.workspaceId.length > 0);
  assert.equal(typeof evidence.fileId, "string");
  assert.ok(evidence.fileId.length > 0);
  assert.ok(
    expected.source.kind === "uncommitted"
      ? ["staged", "unstaged", "untracked"].includes(evidence.layer)
      : evidence.layer === "comparison",
  );
  assert.equal(evidence.callPhase, "resolved");
  assert.equal(evidence.snapshotRevision, evidence.revision);
  assert.equal(evidence.revision, evidence.resultRevision);
  assert.equal(evidence.fileId, evidence.resultFileId);
  assert.equal(evidence.layer, evidence.resultLayer);
  assert.equal(evidence.loading, false);
  assert.equal(evidence.visibleErrorCount, 0);
  assert.equal(evidence.diffHeaderVisible, true);
  return {
    source: evidence.source,
    workspaceId: evidence.workspaceId,
    snapshotRevision: evidence.snapshotRevision,
    fileId: evidence.fileId,
    layer: evidence.layer,
    revision: evidence.revision,
    status: "ready",
  };
}

/** Wait only for the selected file's existing Native request; screenshot validation must not start IO. */
export async function waitForCodingReviewDiffReadiness(
  page,
  deadline,
  expected = { scopeLabel: "审阅范围：未提交", source: { kind: "uncommitted" } },
) {
  const handle = await page.waitForFunction(readCodingReviewDiffReadiness, expected, {
    timeout: timeout(deadline),
  });
  try {
    return validateCodingReviewDiffReadiness(await handle.jsonValue(), expected);
  } finally {
    await handle.dispose();
  }
}

/** 将每个 DOM/IPC 步骤限制在本地期限，避免一个缺失 selector 消耗整轮真窗预算。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/** ReviewShell's ResizeObserver measures contentRect, so borders and padding must leave the measured width. */
export function reviewContentBoxWidth({
  outerWidth,
  borderLeftWidth,
  borderRightWidth,
  paddingLeft,
  paddingRight,
}) {
  const widths = [outerWidth, borderLeftWidth, borderRightWidth, paddingLeft, paddingRight];
  if (
    widths.some((value) => !Number.isFinite(value) || value < 0) ||
    outerWidth < borderLeftWidth + borderRightWidth + paddingLeft + paddingRight
  ) {
    throw new Error("Review shell box metrics are invalid");
  }
  return outerWidth - borderLeftWidth - borderRightWidth - paddingLeft - paddingRight;
}

/** Keep the runner's strict 760px boundary aligned with ReviewShell's content-box ResizeObserver. */
export function reviewLayoutForContentWidth(contentWidth) {
  if (!Number.isFinite(contentWidth) || contentWidth <= 0) {
    throw new Error("Review content width must be a positive finite number");
  }
  return contentWidth < REVIEW_NARROW_CONTENT_WIDTH ? "narrow" : "wide";
}

/** Read both CSS box dimensions so pointer resizing can target the same width that controls layout. */
async function readReviewShellWidths(shell) {
  const metrics = await shell.evaluate((node) => {
    const style = globalThis.getComputedStyle(node);
    const cssPixels = (value) => Number.parseFloat(value);
    return {
      outerWidth: node.getBoundingClientRect().width,
      borderLeftWidth: cssPixels(style.borderLeftWidth),
      borderRightWidth: cssPixels(style.borderRightWidth),
      paddingLeft: cssPixels(style.paddingLeft),
      paddingRight: cssPixels(style.paddingRight),
    };
  });
  return { ...metrics, contentWidth: reviewContentBoxWidth(metrics) };
}

/** 当前 Thread 的唯一 inspector 是 Workbench selector 的身份边界，隐藏缓存会话不可命中。 */
function currentInspector(page) {
  return page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
}

/** 只返回当前可见 Thread 的 Workbench；切会话后隐藏保留 Host 不参加菜单和断言。 */
async function ensureCurrentWorkbench(page, deadline) {
  const inspector = currentInspector(page);
  if ((await inspector.count()) !== 1) {
    throw new Error("当前 Thread 缺少唯一可见的 Workbench inspector");
  }
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: timeout(deadline) });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await workbench.count()) !== 1) {
    throw new Error("当前 Thread 必须只有一个可见 Workbench");
  }
  return workbench;
}

/**
 * @author kongweiguang
 * 诊断读取只等待受限时间并返回安全 DOM 投影；CDP 失联时不会让失败收口越过本轮 deadline。
 */
async function readReviewThreadSurface(page, deadline) {
  const state = await page.waitForFunction(
    readReviewThreadSurfaceState,
    { includeUnready: true },
    { timeout: Math.min(3_000, timeout(deadline)) },
  );
  try {
    return await state.jsonValue();
  } finally {
    await state.dispose();
  }
}

/**
 * @author kongweiguang
 * 只保留可见状态的布尔值和闭集分类；侧栏 Thread/workbench identity 足以识别前台会话，正文与链接始终不进入证据。
 */
async function captureReviewThreadFailure(
  page,
  deadline,
  evidenceDirectory,
  stage,
  code,
  onFailureDiagnostic,
) {
  let surface;
  try {
    surface = await readReviewThreadSurface(page, deadline);
  } catch {
    surface = undefined;
  }
  const defaultSurface = {
    ready: false,
    historyListPresent: false,
    historyBusy: false,
    selectedThreadPresent: false,
    selectedThreadIdValid: false,
    visibleWorkbenchSessionCount: 0,
    workbenchSessionMatchesSelectedThread: false,
    conversationBodyState: "unknown",
    timelineMountedForSelectedThread: false,
  };
  let screenshotCaptured = false;
  try {
    const masks = [
      page.locator(".ja-conversation"),
      page.locator(".ja-navigation-history-list"),
      page.locator('[aria-label="项目列表"]'),
      page.locator("input, textarea, [contenteditable='true']"),
    ];
    await page.screenshot({
      path: join(evidenceDirectory, "thread-readiness-failure.png"),
      animations: "disabled",
      timeout: Math.min(3_000, timeout(deadline)),
      mask: masks,
    });
    screenshotCaptured = true;
  } catch {
    // 截图是有界诊断，不替代原错误或放宽会话前置条件。
  }
  const diagnostic = sanitizeReviewThreadFailureDiagnostic({
    code,
    stage,
    surface: surface ?? defaultSurface,
    screenshotCaptured,
  });
  if (diagnostic !== undefined) {
    await writeFile(
      join(evidenceDirectory, "thread-readiness-failure.json"),
      `${JSON.stringify(diagnostic, null, 2)}\n`,
      "utf8",
    ).catch(() => undefined);
    onFailureDiagnostic?.(diagnostic);
  }
  return diagnostic;
}

/**
 * @author kongweiguang
 * 如果隔离目录没有选中会话，只通过产品“新会话”入口创建真实空 Thread；不发送消息，也不要求空态挂载 Timeline。
 */
async function prepareReviewThread(
  page,
  deadline,
  evidenceDirectory,
  setStage,
  onFailureDiagnostic,
) {
  setStage("driver:thread_prepare");
  let initialState;
  try {
    initialState = await readReviewThreadSurface(page, deadline);
  } catch (error) {
    await captureReviewThreadFailure(
      page,
      deadline,
      evidenceDirectory,
      "driver:thread_prepare",
      "THREAD_SURFACE_NOT_READY",
      onFailureDiagnostic,
    );
    throw error;
  }
  let outcome = "existing_thread";
  if (initialState?.selectedThreadPresent !== true) {
    outcome = "created_empty_thread_via_sidebar";
    try {
      await page.getByRole("button", { name: "新会话", exact: true }).click({
        timeout: timeout(deadline),
      });
    } catch (error) {
      await captureReviewThreadFailure(
        page,
        deadline,
        evidenceDirectory,
        "driver:thread_prepare",
        "THREAD_CREATION_ACTION_FAILED",
        onFailureDiagnostic,
      );
      throw error;
    }
  }
  setStage("driver:thread_surface_ready");
  const surface = await requireCurrentThreadSurface(
    page,
    deadline,
    evidenceDirectory,
    "driver:thread_surface_ready",
    onFailureDiagnostic,
  );
  return {
    outcome,
    currentThreadIdentityVerified: surface.ready,
    timelineRequired: false,
    conversationBodyState: surface.conversationBodyState,
  };
}

/**
 * @author kongweiguang
 * 以导航选择与 Thread Workbench scope 的同一 ID 收口当前会话；空 Thread 的 Timeline 是合法缺省态。
 */
async function requireCurrentThreadSurface(
  page,
  deadline,
  evidenceDirectory,
  stage,
  onFailureDiagnostic,
) {
  try {
    const state = await page.waitForFunction(
      readReviewThreadSurfaceState,
      { includeUnready: false },
      { timeout: Math.min(THREAD_READY_TIMEOUT_MS, timeout(deadline)) },
    );
    try {
      return await state.jsonValue();
    } finally {
      await state.dispose();
    }
  } catch (error) {
    await captureReviewThreadFailure(
      page,
      deadline,
      evidenceDirectory,
      stage,
      "THREAD_SURFACE_NOT_READY",
      onFailureDiagnostic,
    );
    throw error;
  }
}

/**
 * 在 E2E bundle 的窄 nativeInvoke 接缝记录经 DTO 约束的稳定身份、终态与耗时；不保留路径、
 * Diff 正文或文件内容，delegate 始终原样调用真实 Tauri command。
 */
export function installReviewInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_REVIEW_REDESIGN_COUNTS__ = Object.create(null);
  globalThis.__JA_REVIEW_REDESIGN_CALLS__ = [];
  /**
   * Init scripts run in the WebView realm without this module's lexical bindings; keep diagnostic
   * projection local and allowlisted so a successful native Catalog result stays successful.
   */
  function summarizeCatalogSources(catalog) {
    const baseRefs = Array.isArray(catalog?.baseRefs) ? catalog.baseRefs : [];
    const commits = Array.isArray(catalog?.commits) ? catalog.commits : [];
    return {
      headCommitId:
        typeof catalog?.headCommitId === "string" && catalog.headCommitId.length > 0
          ? catalog.headCommitId
          : null,
      refs: baseRefs
        .filter(
          (ref) =>
            typeof ref?.refId === "string" &&
            ref.refId.length > 0 &&
            typeof ref?.label === "string" &&
            ref.label.length > 0 &&
            ["base", "local", "remote"].includes(ref?.kind),
        )
        .map((ref) => ({ refId: ref.refId, label: ref.label, kind: ref.kind })),
      commitIds: commits
        .filter((commit) => typeof commit?.commitId === "string" && commit.commitId.length > 0)
        .map((commit) => commit.commitId),
    };
  }
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const observed = new Set([
      "ja_review_catalog",
      "ja_review_snapshot",
      "ja_review_capture_snapshot",
      "ja_review_file_diff",
      "ja_review_apply",
      "ja_review_cancel",
      "ja_turn_change_preview_open",
      "ja_turn_change_preview_read",
      "ja_turn_change_preview_close",
      "ja_turn_change_set_read",
    ]);
    const command = request?.command;
    const input = request?.args?.input;
    const startedAt = observed.has(command) ? performance.now() : undefined;
    const tracked = observed.has(command)
      ? {
          command,
          phase: "started",
          workspaceId: typeof input?.workspaceId === "string" ? input.workspaceId : null,
          source:
            input?.source !== null && typeof input?.source === "object"
              ? { ...input.source }
              : null,
          expectedSnapshotRevision:
            typeof input?.expectedSnapshotRevision === "string"
              ? input.expectedSnapshotRevision
              : null,
          revision: typeof input?.revision === "string" ? input.revision : null,
          operationId: typeof input?.operationId === "string" ? input.operationId : null,
          fileId: typeof input?.fileId === "string" ? input.fileId : null,
          layer: typeof input?.layer === "string" ? input.layer : null,
        }
      : undefined;
    if (tracked !== undefined) globalThis.__JA_REVIEW_REDESIGN_CALLS__.push(tracked);
    const append = (phase) => {
      if (!observed.has(command)) return;
      const counts = globalThis.__JA_REVIEW_REDESIGN_COUNTS__;
      const bucket = counts[command] ?? { start: 0, resolved: 0, rejected: 0 };
      bucket[phase] += 1;
      counts[command] = bucket;
    };
    append("start");
    try {
      const gate = globalThis.__JA_REVIEW_CATALOG_TEST_GATE__;
      if (command === "ja_review_catalog" && gate?.armed === true) {
        gate.armed = false;
        gate.entered = true;
        await new Promise((release) => {
          gate.release = release;
        });
        gate.released = true;
        if (gate.rejectFirst === true && gate.failureInjected !== true) {
          gate.failureInjected = true;
          throw Object.assign(new Error("E2E catalog retry boundary"), {
            code: "RUNTIME_UNAVAILABLE",
          });
        }
      }
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      append("resolved");
      if (tracked !== undefined) {
        tracked.phase = "resolved";
        tracked.elapsedMs = Math.max(0, Math.round(performance.now() - startedAt));
        if (command === "ja_review_catalog") {
          tracked.catalogSources = summarizeCatalogSources(result);
        } else if (command === "ja_review_snapshot" || command === "ja_review_capture_snapshot") {
          tracked.resultRevision = typeof result?.revision === "string" ? result.revision : null;
          tracked.fileCount = Array.isArray(result?.files) ? result.files.length : null;
          if (command === "ja_review_snapshot") {
            tracked.comparisonFileCount = Array.isArray(result?.files)
              ? result.files.filter((file) => file?.layer === "comparison").length
              : null;
            tracked.capabilities = {
              present: result?.capabilities !== null && typeof result?.capabilities === "object",
              stage: result?.capabilities?.stage === true,
              unstage: result?.capabilities?.unstage === true,
              revert: result?.capabilities?.revert === true,
            };
          }
          if (command === "ja_review_capture_snapshot") {
            tracked.snapshotId = typeof result?.snapshotId === "string" ? result.snapshotId : null;
            tracked.snapshotHash =
              typeof result?.snapshotHash === "string" ? result.snapshotHash : null;
            tracked.attachmentId =
              typeof result?.attachmentId === "string" ? result.attachmentId : null;
          }
        } else if (command === "ja_review_file_diff") {
          tracked.resultFileId = typeof result?.fileId === "string" ? result.fileId : null;
          tracked.resultLayer = typeof result?.layer === "string" ? result.layer : null;
          tracked.resultRevision = typeof result?.revision === "string" ? result.revision : null;
        }
      }
      return result;
    } catch (error) {
      append("rejected");
      if (tracked !== undefined) {
        const code = typeof error?.code === "string" ? error.code : "";
        tracked.phase = "rejected";
        tracked.elapsedMs = Math.max(0, Math.round(performance.now() - startedAt));
        tracked.errorCode = /^[A-Z][A-Z0-9_]{0,63}$/u.test(code) ? code : "INVOKE_FAILED";
      }
      throw error;
    }
  };
}

/** Capture Vite source-module resource URLs before Chromium's bounded timing buffer evicts them. */
export function installReviewResourceObserver() {
  globalThis.__JA_REVIEW_SOURCE_RESOURCE_URLS__ = [];
  globalThis.performance.setResourceTimingBufferSize?.(5_000);
  if (typeof globalThis.PerformanceObserver !== "function") return;
  const remember = (entries) => {
    const urls = globalThis.__JA_REVIEW_SOURCE_RESOURCE_URLS__;
    for (const entry of entries) {
      if (
        typeof entry.name === "string" &&
        /\/(?:src|@fs)\/.*\.(?:tsx?|jsx?)(?:$|\?)/iu.test(entry.name) &&
        !urls.includes(entry.name)
      ) {
        urls.push(entry.name);
      }
    }
  };
  const observer = new PerformanceObserver((list) => remember(list.getEntries()));
  observer.observe({ type: "resource", buffered: true });
  remember(globalThis.performance.getEntriesByType("resource"));
  globalThis.__JA_REVIEW_SOURCE_RESOURCE_OBSERVER__ = observer;
}

/** 仅为真实来源验收保留 catalog 的 ref kind 与 commit ID，不把作者、主题或文件路径带入诊断。 */
export function summarizeReviewCatalogSources(catalog) {
  const baseRefs = Array.isArray(catalog?.baseRefs) ? catalog.baseRefs : [];
  const commits = Array.isArray(catalog?.commits) ? catalog.commits : [];
  return {
    headCommitId:
      typeof catalog?.headCommitId === "string" && catalog.headCommitId.length > 0
        ? catalog.headCommitId
        : null,
    refs: baseRefs
      .filter(
        (ref) =>
          typeof ref?.refId === "string" &&
          ref.refId.length > 0 &&
          typeof ref?.label === "string" &&
          ref.label.length > 0 &&
          ["base", "local", "remote"].includes(ref?.kind),
      )
      .map((ref) => ({ refId: ref.refId, label: ref.label, kind: ref.kind })),
    commitIds: commits
      .filter((commit) => typeof commit?.commitId === "string" && commit.commitId.length > 0)
      .map((commit) => commit.commitId),
  };
}

/** 只读取闭集计数，避免 E2E 报告携带任何 native 参数或响应。 */
async function reviewInvokeCounts(page) {
  return page.evaluate(() => {
    const source = globalThis.__JA_REVIEW_REDESIGN_COUNTS__ ?? {};
    return Object.fromEntries(
      Object.entries(source).map(([command, phases]) => [
        command,
        {
          start: Number(phases?.start ?? 0),
          resolved: Number(phases?.resolved ?? 0),
          rejected: Number(phases?.rejected ?? 0),
        },
      ]),
    );
  });
}

/** 读取单个命令阶段计数；不存在的命令按零处理。 */
function invokeCount(counts, command, phase = "start") {
  return Number(counts?.[command]?.[phase] ?? 0);
}

/**
 * 先以真实 Host 状态 RPC 证明 Java runtime 已 ready，再等待 Workspace 行收到同代 React 状态投影；
 * Shell 可先于连接渲染，且 Playwright 不等待异步 waitForFunction 谓词，因此 RPC 在 Node 侧轮询。
 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const readinessDeadline = Math.min(deadline, Date.now() + STEP_TIMEOUT_MS);
  let runtime;
  let lastObservation = { kind: "invoke_unavailable" };
  while (Date.now() < readinessDeadline) {
    const observation = await page.evaluate(
      async (timeoutMs) => {
        const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
        if (typeof invoke !== "function") return { kind: "invoke_unavailable" };
        const startedAt = performance.now();
        let timer;
        const timeoutResult = new Promise((resolvePromise) => {
          timer = setTimeout(() => resolvePromise({ kind: "timeout" }), timeoutMs);
        });
        const stateResult = Promise.resolve()
          .then(() => invoke("ja_runtime_state", {}))
          .then((state) => ({ kind: "state", state }))
          .catch((error) => ({
            kind: "rpc_error",
            errorCode:
              typeof error?.code === "string" && /^[A-Z0-9_]{1,64}$/u.test(error.code)
                ? error.code
                : "invoke_failed",
          }));
        const result = await Promise.race([stateResult, timeoutResult]);
        clearTimeout(timer);
        const durationMs = Math.round(performance.now() - startedAt);
        if (result.kind !== "state") return { ...result, durationMs };
        return {
          kind: "state",
          durationMs,
          status: typeof result.state?.status === "string" ? result.state.status : "unknown",
          generation: Number.isSafeInteger(result.state?.generation)
            ? result.state.generation
            : null,
          serverInstanceIdPresent:
            typeof result.state?.serverInstanceId === "string" &&
            result.state.serverInstanceId.length > 0,
        };
      },
      // Native connect/initialize/ready uses a 10-second budget; wait past it for the actual IPC result.
      Math.max(1, Math.min(15_000, readinessDeadline - Date.now())),
    );
    if (
      observation.kind === "state" &&
      observation.status === "ready" &&
      Number.isSafeInteger(observation.generation) &&
      observation.generation > 0 &&
      observation.serverInstanceIdPresent === true
    ) {
      runtime = {
        command: "ja_runtime_state",
        status: observation.status,
        generation: observation.generation,
        serverInstanceIdPresent: true,
        stateRpcDurationMs: observation.durationMs,
      };
      break;
    }
    lastObservation = observation;
    if (observation.kind === "timeout") {
      throw new Error(`runtime state RPC did not settle within ${observation.durationMs}ms`);
    }
    await page.waitForTimeout(Math.min(250, Math.max(1, readinessDeadline - Date.now())));
  }
  assert.ok(
    runtime !== undefined,
    `runtime state RPC did not report ready; last observation: ${JSON.stringify(lastObservation)}`,
  );
  assert.equal(runtime.status, "ready", "runtime state RPC did not report ready");
  assert.ok(Number.isSafeInteger(runtime.generation) && runtime.generation > 0);
  assert.equal(runtime.serverInstanceIdPresent, true);
  const projectedReady = page.locator(".ja-navigation-sidebar .ja-workspace-scope-status.is-ready");
  await projectedReady.waitFor({ state: "visible", timeout: timeout(deadline) });
  return { ...runtime, projectedReady: true };
}

/**
 * 经 E2E composition 的确定性 picker 点击真实“添加项目”；picker 只选择隔离路径，后续
 * Workspace identity、Git snapshot 和 Diff 仍全部由 Java/Rust/Tauri 生产链路签发。
 */
async function selectFixtureProject(page, deadline) {
  const selected = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selected.count()) === 0) {
    await page.getByRole("button", { name: "添加项目", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await selected.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/**
 * @author kongweiguang
 * 先确认真实侧栏 Thread 与可见 Workbench scope 对齐，再从该宿主打开 Review，避免误测隐藏 Thread。
 */
async function openReview(page, deadline, evidenceDirectory, onFailureDiagnostic) {
  await requireCurrentThreadSurface(
    page,
    deadline,
    evidenceDirectory,
    "driver:review_open",
    onFailureDiagnostic,
  );
  const workbench = await ensureCurrentWorkbench(page, deadline);
  let tab = workbench.locator('[data-workbench-tab="review"]');
  if ((await tab.count()) === 0) {
    await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: timeout(deadline),
    });
    const menus = page.getByRole("menu");
    await menus.waitFor({ state: "visible", timeout: timeout(deadline) });
    if ((await menus.count()) !== 1) throw new Error("新建标签菜单必须唯一");
    await menus
      .getByRole("menuitem")
      .filter({ hasText: "审查" })
      .first()
      .click({
        timeout: timeout(deadline),
      });
    tab = workbench.locator('[data-workbench-tab="review"]');
  }
  await tab.click({ timeout: timeout(deadline) });
  const panel = workbench.locator('[data-tab-panel="review"]');
  await panel.waitFor({ state: "visible", timeout: timeout(deadline) });
  const shell = workbench.locator(REVIEW_SHELL);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  return shell;
}

/** Radix Select option 位于 portal；分组和主题切换都从可访问 trigger 出发。 */
async function chooseSelectOption(page, trigger, optionName, deadline) {
  await trigger.click({ timeout: timeout(deadline) });
  await page.getByRole("option", { name: optionName, exact: true }).click({
    timeout: timeout(deadline),
  });
}

/**
 * Workbench 的来源入口位于 ReviewShell 外部；只接受唯一可见的生产 trigger，避免缓存面板误命中。
 */
export async function requireVisibleReviewScopeTrigger(page, deadline) {
  const trigger = page.locator(".ja-review-source-trigger:visible");
  await trigger.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await trigger.count(), 1, "Review scope menu trigger must be unique");
  assert.equal(await trigger.isVisible(), true, "Review scope menu trigger must be visible");
  return trigger;
}

/**
 * Radix restores focus after the source menu finishes closing; wait for that observable boundary
 * before recording focus, and expose only safe DOM identity facts if the boundary never arrives.
 */
async function waitForReviewScopeFocus(page, deadline) {
  try {
    const handle = await page.waitForFunction(
      () => {
        const triggers = [
          ...globalThis.document.querySelectorAll(".ja-review-source-trigger"),
        ].filter(
          (node) =>
            node.getClientRects().length > 0 &&
            globalThis.getComputedStyle(node).visibility === "visible",
        );
        return triggers.length === 1 && triggers[0] === globalThis.document.activeElement;
      },
      undefined,
      { timeout: timeout(deadline) },
    );
    await handle.dispose();
    const trigger = await requireVisibleReviewScopeTrigger(page, deadline);
    assert.equal(
      await trigger.evaluate((node) => node === globalThis.document.activeElement),
      true,
      "Review scope trigger must own focus after the focus wait",
    );
    return trigger;
  } catch (error) {
    const focusFacts = await page.evaluate(() => {
      const triggers = [
        ...globalThis.document.querySelectorAll(".ja-review-source-trigger"),
      ].filter(
        (node) =>
          node.getClientRects().length > 0 &&
          globalThis.getComputedStyle(node).visibility === "visible",
      );
      const trigger = triggers.length === 1 ? triggers[0] : null;
      const active = globalThis.document.activeElement;
      return {
        visibleTriggerCount: triggers.length,
        triggerIsConnected: trigger?.isConnected ?? false,
        activeIsTrigger: trigger !== null && active === trigger,
        activeElement: active
          ? {
              tagName: active.tagName,
              role: active.getAttribute("role"),
              ariaLabel: active.getAttribute("aria-label"),
              className: typeof active.className === "string" ? active.className : null,
            }
          : null,
      };
    });
    throw new Error(`Workbench Review scope focus did not return: ${JSON.stringify(focusFacts)}`, {
      cause: error,
    });
  }
}

/** 等待 Workbench 外部的唯一来源入口显示指定标签，保持来源切换的真实界面身份。 */
export async function waitForReviewScopeLabel(page, expectedLabel, deadline) {
  const handle = await page.waitForFunction(
    (expected) => {
      const triggers = [
        ...globalThis.document.querySelectorAll(".ja-review-source-trigger"),
      ].filter((node) => node.getClientRects().length > 0);
      return triggers.length === 1 && triggers[0].getAttribute("aria-label") === expected;
    },
    expectedLabel,
    { timeout: timeout(deadline) },
  );
  await handle.dispose();
  const trigger = await requireVisibleReviewScopeTrigger(page, deadline);
  assert.equal(await trigger.getAttribute("aria-label"), expectedLabel);
  return trigger;
}

/** 从生产 Workbench 的范围菜单选择一项，保留菜单关闭后的键盘焦点和最终可见标签。 */
export async function selectReviewScopeOption(page, groupLabel, optionLabel, deadline) {
  const trigger = await requireVisibleReviewScopeTrigger(page, deadline);
  await trigger.click({ timeout: timeout(deadline) });
  const group = page.getByRole("menuitem", { name: groupLabel, exact: true });
  await group.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await group.count(), 1, `Review scope group must be unique: ${groupLabel}`);
  await group.hover({ timeout: timeout(deadline) });
  const option = page.getByRole("menuitem", { name: optionLabel, exact: true });
  if (!(await option.isVisible().catch(() => false))) {
    await group.focus();
    await group.press("ArrowRight");
  }
  await option.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await option.count(), 1, `Review scope option must be unique: ${optionLabel}`);
  await option.click({ timeout: timeout(deadline) });
  const expectedScopeLabel = `审阅范围：${groupLabel === "未提交" ? "未提交" : optionLabel}`;
  await waitForReviewScopeLabel(page, expectedScopeLabel, deadline);
  const menusClosed = await page.waitForFunction(
    () =>
      ![...globalThis.document.querySelectorAll('.ja-review-source-menu[role="menu"]')].some(
        (node) =>
          node.getClientRects().length > 0 &&
          globalThis.getComputedStyle(node).visibility === "visible",
      ),
    undefined,
    { timeout: timeout(deadline) },
  );
  await menusClosed.dispose();
  const focusedTrigger = await waitForReviewScopeFocus(page, deadline);
  const scopeLabel = await focusedTrigger.getAttribute("aria-label");
  const focusReturned = await focusedTrigger.evaluate(
    (node) => node === globalThis.document.activeElement,
  );
  assert.equal(focusReturned, true, "Review scope selection must return focus after menu close");
  return { trigger: focusedTrigger, scopeLabel, focusReturned };
}

/** 等待真实 Git Catalog 成功，避免在初次异步加载期间打开尚未挂载比较项的菜单。 */
async function waitForReviewCatalogReady(page, deadline) {
  const handle = await page.waitForFunction(
    () => {
      const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
      if (calls.some((call) => call.command === "ja_review_catalog" && call.phase === "started"))
        return false;
      const latest = calls
        .filter((call) => call.command === "ja_review_catalog" && call.phase === "resolved")
        .at(-1);
      return latest?.catalogSources ?? false;
    },
    undefined,
    { timeout: timeout(deadline) },
  );
  const catalogSources = await handle.jsonValue();
  await handle.dispose();
  return catalogSources;
}

/** 首次 Catalog 受控失败后，从可见菜单重试；冻结 Turn 时只允许轻量目录读取。 */
async function captureCatalogLoadingMenu(page, evidenceDirectory, deadline) {
  const gateStarted = await page.waitForFunction(
    () => globalThis.__JA_REVIEW_CATALOG_TEST_GATE__?.entered === true,
    undefined,
    { timeout: timeout(deadline) },
  );
  await gateStarted.dispose();
  try {
    const trigger = await requireVisibleReviewScopeTrigger(page, deadline);
    await trigger.click({ timeout: timeout(deadline) });
    const menu = page.locator('.ja-review-source-menu[role="menu"]:visible');
    await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
    assert.equal(await menu.count(), 1, "visible Workbench must have one Review source menu");
    const loading = menu.getByRole("status");
    await loading.waitFor({ state: "visible", timeout: timeout(deadline) });
    assert.match((await loading.innerText()).trim(), /正在读取 Git 来源/u);
    const initialMenuFacts = await menu.evaluate((node) => {
      globalThis.__JA_REVIEW_SOURCE_MENU_NODE__ = node;
      const menuItems = [...node.querySelectorAll('[role="menuitem"]')].map((item) =>
        item.textContent?.trim(),
      );
      return {
        menuOpen: node.getAttribute("data-state") === "open",
        gitGroupsAbsent: !menuItems.some((label) => ["未提交", "比较"].includes(label)),
        loadingStatusVisible: node.querySelector('[role="status"]')?.getClientRects().length > 0,
      };
    });
    assert.equal(initialMenuFacts.menuOpen, true);
    assert.equal(initialMenuFacts.gitGroupsAbsent, true);
    assert.equal(initialMenuFacts.loadingStatusVisible, true);
    await page.screenshot({
      path: join(evidenceDirectory, "coding-review-source-catalog-loading.png"),
      animations: "disabled",
    });

    const snapshotsSettled = await page.waitForFunction(
      () =>
        (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [])
          .filter((call) => call.command === "ja_review_snapshot")
          .every((call) => call.phase !== "started"),
      undefined,
      { timeout: timeout(deadline) },
    );
    await snapshotsSettled.dispose();
    await page.evaluate(() => globalThis.__JA_REVIEW_CATALOG_TEST_GATE__?.release?.());
    await page.waitForFunction(
      () =>
        globalThis.document
          .querySelector('.ja-review-source-menu[role="menu"] [role="status"]')
          ?.textContent?.includes("运行时暂不可用") === true,
      undefined,
      { timeout: timeout(deadline) },
    );
    const retryItem = menu.getByRole("menuitem", { name: "重新读取 Git 来源", exact: true });
    await retryItem.waitFor({ state: "visible", timeout: timeout(deadline) });
    const failedCatalog = await page.evaluate(() => {
      const call = (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [])
        .filter((entry) => entry.command === "ja_review_catalog")
        .at(-1);
      return {
        phase: call?.phase,
        errorCode: call?.errorCode,
        failureInjected: globalThis.__JA_REVIEW_CATALOG_TEST_GATE__?.failureInjected === true,
      };
    });
    assert.deepEqual(failedCatalog, {
      phase: "rejected",
      errorCode: "RUNTIME_UNAVAILABLE",
      failureInjected: true,
    });
    await page.screenshot({
      path: join(evidenceDirectory, "coding-review-source-catalog-error.png"),
      animations: "disabled",
    });

    const latestTurn = menu.getByRole("menuitem", { name: "最后一轮", exact: true });
    const frozenTurnAvailable = (await latestTurn.count()) === 1;
    if (frozenTurnAvailable) {
      await latestTurn.click({ timeout: timeout(deadline) });
      await menu.waitFor({ state: "hidden", timeout: timeout(deadline) });
      await waitForReviewScopeLabel(page, "审阅范围：最后一轮", deadline);
      await trigger.click({ timeout: timeout(deadline) });
      await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
    }

    const selectedLabelBeforeRetry = await trigger.getAttribute("aria-label");
    assert.ok(selectedLabelBeforeRetry);
    await menu.getByRole("status").waitFor({ state: "visible", timeout: timeout(deadline) });
    await menu
      .getByRole("menuitem", { name: "重新读取 Git 来源", exact: true })
      .waitFor({ state: "visible", timeout: timeout(deadline) });
    const retryBaseline = await page.evaluate(() => {
      const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
      return {
        callIndex: calls.length,
        catalogStarts: calls.filter((call) => call.command === "ja_review_catalog").length,
        snapshotStarts: calls.filter((call) => call.command === "ja_review_snapshot").length,
        diffStarts: calls.filter((call) => call.command === "ja_review_file_diff").length,
      };
    });
    await menu.getByRole("menuitem", { name: "重新读取 Git 来源", exact: true }).click({
      timeout: timeout(deadline),
    });
    await menu.waitFor({ state: "hidden", timeout: timeout(deadline) });
    const focusedTrigger = await waitForReviewScopeFocus(page, deadline);
    const retryFocusReturned = await focusedTrigger.evaluate(
      (node) => node === globalThis.document.activeElement,
    );
    const retriedCatalogHandle = await page.waitForFunction(
      ({ startIndex }) => {
        const result = (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [])
          .slice(startIndex)
          .filter((call) => call.command === "ja_review_catalog")
          .at(-1);
        return result === undefined || result.phase === "started" ? false : result;
      },
      { startIndex: retryBaseline.callIndex },
      { timeout: timeout(deadline) },
    );
    const retriedCatalog = await retriedCatalogHandle.jsonValue();
    await retriedCatalogHandle.dispose();
    assert.equal(retriedCatalog.phase, "resolved", "catalog menu retry must succeed");
    assert.ok(retriedCatalog.catalogSources, "catalog retry must return actual Git sources");
    const retryDeltas = await page.evaluate((baseline) => {
      const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
      return {
        catalogStartDelta:
          calls.filter((call) => call.command === "ja_review_catalog").length -
          baseline.catalogStarts,
        snapshotStartDelta:
          calls.filter((call) => call.command === "ja_review_snapshot").length -
          baseline.snapshotStarts,
        diffStartDelta:
          calls.filter((call) => call.command === "ja_review_file_diff").length -
          baseline.diffStarts,
      };
    }, retryBaseline);
    assert.equal(retryDeltas.catalogStartDelta, 1, "menu retry must make one Catalog request");
    if (frozenTurnAvailable) {
      assert.deepEqual(retryDeltas, {
        catalogStartDelta: 1,
        snapshotStartDelta: 0,
        diffStartDelta: 0,
      });
    }
    await waitForReviewScopeLabel(page, selectedLabelBeforeRetry, deadline);
    assert.equal(retryFocusReturned, true, "Catalog retry must return focus to its scope trigger");
    await trigger.click({ timeout: timeout(deadline) });
    await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
    await page.getByRole("menuitem", { name: "未提交", exact: true }).waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
    await page.getByRole("menuitem", { name: "比较", exact: true }).waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
    const gitGroupsAvailable = await menu.evaluate((node) => {
      const labels = [...node.querySelectorAll('[role="menuitem"]')].map((item) =>
        item.textContent?.trim(),
      );
      return labels.includes("未提交") && labels.includes("比较");
    });
    assert.equal(gitGroupsAvailable, true, "successful retry must restore Git source groups");
    assert.equal(
      await menu.getByRole("menuitem", { name: "重新读取 Git 来源", exact: true }).count(),
      0,
      "successful retry must clear the retry item",
    );
    await page.screenshot({
      path: join(evidenceDirectory, "coding-review-source-catalog-ready.png"),
      animations: "disabled",
    });
    await page.keyboard.press("Escape");
    await menu.waitFor({ state: "hidden", timeout: timeout(deadline) });
    return {
      loadingStatusVisible: true,
      initiallyEmptyOfGitGroups: true,
      catalogFailureInjected: failedCatalog.failureInjected,
      catalogErrorVisible: true,
      retryMenuItemAvailable: true,
      frozenTurnAvailable,
      frozenTurnSelectedBeforeRetry: frozenTurnAvailable,
      retryFocusReturned,
      catalogRetryResolved: true,
      selectedScopePreserved: true,
      ...retryDeltas,
      gitGroupsAvailableAfterRetry: gitGroupsAvailable,
      screenshots: [
        "coding-review-source-catalog-loading.png",
        "coding-review-source-catalog-error.png",
        "coding-review-source-catalog-ready.png",
      ],
    };
  } finally {
    await page
      .evaluate(() => globalThis.__JA_REVIEW_CATALOG_TEST_GATE__?.release?.())
      .catch(() => undefined);
    await page
      .evaluate(() => delete globalThis.__JA_REVIEW_SOURCE_MENU_NODE__)
      .catch(() => undefined);
  }
}

/** 选择“未提交→全部”；来源未变时复用同 Workspace 已完成快照，来源变化时要求选择后的新 RPC。 */
async function chooseAllUncommitted(page, evidenceDirectory, deadline, verifyCatalogLoadingMenu) {
  const catalogMenuReadiness = verifyCatalogLoadingMenu
    ? await captureCatalogLoadingMenu(page, evidenceDirectory, deadline)
    : await waitForReviewCatalogReady(page, deadline).then(() => null);
  const beforeSelection = await page.evaluate(
    () => (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? []).length,
  );
  const sourceBeforeSelection = await (
    await requireVisibleReviewScopeTrigger(page, deadline)
  ).getAttribute("aria-label");
  const requiresFreshSnapshot = sourceBeforeSelection !== "审阅范围：未提交";
  const selection = await selectReviewScopeOption(page, "未提交", "全部", deadline);
  assert.equal(selection.scopeLabel, "审阅范围：未提交");
  assert.equal(selection.focusReturned, true, "uncommitted scope selection must return focus");
  await page.evaluate(
    ({ startIndex, requiresFresh, sourceLabel }) => {
      globalThis.__JA_REVIEW_SCOPE_SELECTION_WAIT__ = {
        startIndex,
        requiresFresh,
        sourceBefore: sourceLabel === "审阅范围：未提交" ? "uncommitted" : "other",
      };
    },
    {
      startIndex: beforeSelection,
      requiresFresh: requiresFreshSnapshot,
      sourceLabel: sourceBeforeSelection,
    },
  );
  const snapshotHandle = await page.waitForFunction(
    findSettledUncommittedReviewSnapshot,
    { startIndex: beforeSelection, requiresFresh: requiresFreshSnapshot },
    { timeout: timeout(deadline) },
  );
  const snapshot = await snapshotHandle.jsonValue();
  await snapshotHandle.dispose();
  assert.equal(snapshot.phase, "resolved", "uncommitted Review snapshot must resolve");
  assert.ok(snapshot.workspaceId, "uncommitted Review snapshot must bind a Workspace");
  assert.ok(snapshot.resultRevision, "uncommitted Review snapshot must bind a revision");
  return catalogMenuReadiness;
}

/** 读取稳定文件身份，不复制完整路径到报告。 */
async function fileIdentity(file) {
  return {
    fileId: await file.getAttribute("data-review-file-id"),
    layer: await file.getAttribute("data-review-layer"),
    name: basename((await file.getAttribute("aria-label")) ?? "unknown"),
  };
}

/** 等待 shell 暴露与当前选择一致的 Diff 身份，严格捕获旧请求晚到覆盖新选择。 */
async function waitForSelectedIdentity(page, expected, deadline) {
  const completion = await page.waitForFunction(
    ({ fileId, layer }) => {
      const shell = globalThis.document.querySelector(
        '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench [data-ja-review-shell]',
      );
      const diff = shell?.querySelector(
        "[data-review-selected-file-id][data-review-selected-layer]",
      );
      if (
        diff?.getAttribute("data-review-selected-file-id") !== fileId ||
        diff?.getAttribute("data-review-selected-layer") !== layer
      )
        return false;
      const visibleAlert = [...(shell?.querySelectorAll('[role="alert"]') ?? [])].some(
        (element) => element.getClientRects().length > 0,
      );
      if (visibleAlert) return { state: "error" };
      if (diff.querySelector('[role="status"]') !== null) return false;
      if (diff.querySelector(".ja-review-diff-header")?.getClientRects().length > 0)
        return { state: "ready" };
      return false;
    },
    { fileId: expected.fileId, layer: expected.layer },
    { timeout: timeout(deadline) },
  );
  const result = await completion.jsonValue();
  await completion.dispose();
  assert.equal(
    result?.state,
    "ready",
    "selected Review file entered an error state before its Diff loaded",
  );
}

/**
 * 验证同一路径 staged/unstaged 两个稳定身份均可点击并显示自己的 Diff；目标位于深层目录，
 * 通过真实搜索临时展开命中祖先，完成后清空查询以恢复进入验收前的树折叠状态。
 */
async function verifyLayeredFileSelection(page, shell, deadline) {
  const narrow = (await shell.getAttribute("data-review-layout")) === "narrow";
  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  await search.fill("partially-staged.ts");
  const candidates = shell.locator(REVIEW_FILE).filter({ hasText: "partially-staged.ts" });
  await candidates.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  const count = await candidates.count();
  assert.equal(count, 2, "部分暂存文件必须按两个 layer 展示");
  const identities = [];
  for (let index = 0; index < count; index += 1) {
    const row = candidates.nth(index);
    const identity = await fileIdentity(row);
    identities.push(identity);
    await row.click({ timeout: timeout(deadline) });
    await waitForSelectedIdentity(page, identity, deadline);
    if (narrow) {
      await shell.getByRole("button", { name: "返回变更文件", exact: true }).click({
        timeout: timeout(deadline),
      });
      await shell.locator(REVIEW_TREE).waitFor({ state: "visible", timeout: timeout(deadline) });
    }
  }
  assert.deepEqual(new Set(identities.map(({ layer }) => layer)), new Set(["staged", "unstaged"]));
  await search.fill("");
  return identities;
}

/** 先筛选展开虚拟化祖先并验证 latest-wins，临时放宽视口后再恢复原 Review 宽度。 */
async function verifyLatestWinsSelection(page, shell, deadline, evidenceDirectory) {
  const originalViewport = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
    deviceScaleFactor: globalThis.devicePixelRatio,
    outerWidth: globalThis.outerWidth,
    outerHeight: globalThis.outerHeight,
  }));
  if (
    !Number.isSafeInteger(originalViewport.width) ||
    !Number.isSafeInteger(originalViewport.height) ||
    !Number.isFinite(originalViewport.deviceScaleFactor) ||
    originalViewport.width < 1 ||
    originalViewport.height < 1
  ) {
    throw new Error("快速选择验收缺少有效的原生 surface 尺寸");
  }
  const originalLayout = await shell.getAttribute("data-review-layout");
  const originalReviewWidth = await shell.evaluate((node) => node.getBoundingClientRect().width);
  if (!Number.isFinite(originalReviewWidth) || originalReviewWidth <= 0) {
    throw new Error("快速选择验收缺少有效的原始 Review 容器宽度");
  }
  const wideViewport = {
    width: Math.max(originalViewport.width, 1600),
    height: originalViewport.height,
  };
  const cdp = await page.context().newCDPSession(page);
  let selected;
  try {
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: wideViewport.width,
      height: wideViewport.height,
      deviceScaleFactor: originalViewport.deviceScaleFactor,
      mobile: false,
    });
    await page.waitForFunction(
      ({ width, height }) => globalThis.innerWidth === width && globalThis.innerHeight === height,
      wideViewport,
      { timeout: timeout(deadline) },
    );
    await resizeReviewContainer(page, shell, 760, deadline);
    assert.equal(await shell.getAttribute("data-review-layout"), "wide");
    const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
    await search.fill(".ts");
    const fileAt = (path, layer) =>
      shell.getByRole("treeitem", {
        name: `查看 ${path} 的${layer === "unstaged" ? "未暂存" : "已暂存"}变更`,
        exact: true,
      });
    const first = fileAt("src/domain/entity/modified.ts", "unstaged");
    const second = fileAt("src/renamed-after.ts", "staged");
    await first.waitFor({ state: "visible", timeout: timeout(deadline) });
    await second.waitFor({ state: "visible", timeout: timeout(deadline) });
    assert.equal(await first.count(), 1, "latest-wins 的普通文本修改行必须唯一");
    assert.equal(await second.count(), 1, "latest-wins 的 staged rename 行必须唯一");
    selected = await fileIdentity(second);
    await first.click({ noWaitAfter: true, timeout: timeout(deadline) });
    await second.click({ noWaitAfter: true, timeout: timeout(deadline) });
    await waitForSelectedIdentity(page, selected, deadline);
    await search.fill("");
  } finally {
    await cdp.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    await cdp.detach().catch(() => undefined);
    await page.waitForFunction(
      ({ width, height }) => globalThis.innerWidth === width && globalThis.innerHeight === height,
      originalViewport,
      { timeout: timeout(deadline) },
    );
    await resizeReviewContainer(page, shell, originalReviewWidth, deadline);
  }

  const restoredSurface = await page.evaluate(() => {
    const shell = globalThis.document.querySelector(
      '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench [data-ja-review-shell]',
    );
    const rect = shell?.getBoundingClientRect();
    return {
      viewport: {
        width: globalThis.innerWidth,
        height: globalThis.innerHeight,
        deviceScaleFactor: globalThis.devicePixelRatio,
        outerWidth: globalThis.outerWidth,
        outerHeight: globalThis.outerHeight,
      },
      review: {
        layout: shell?.getAttribute("data-review-layout") ?? null,
        x: rect === undefined ? null : Math.round(rect.x),
        y: rect === undefined ? null : Math.round(rect.y),
        width: rect === undefined ? null : Math.round(rect.width),
        height: rect === undefined ? null : Math.round(rect.height),
      },
    };
  });
  assert.deepEqual(restoredSurface.viewport, originalViewport);
  assert.equal(restoredSurface.review.layout, originalLayout);
  assert.ok(restoredSurface.review.width > 0 && restoredSurface.review.height > 0);
  const restoredScreenshot = "latest-wins-restored-surface.png";
  await page.screenshot({
    path: join(evidenceDirectory, restoredScreenshot),
    animations: "disabled",
  });
  return {
    ...selected,
    temporaryViewport: {
      mode: "cdp_emulated",
      width: wideViewport.width,
      height: wideViewport.height,
      nativeWindowResized: false,
    },
    restoredSurface,
    restoredScreenshot,
  };
}

/** 筛选先展开虚拟树祖先，再只触发一次冲突 Diff，验证清晰错误且保留手动重读。 */
async function verifyConflictDiagnostic(page, shell, deadline) {
  if ((await shell.getAttribute("data-review-layout")) === "narrow") {
    await shell.getByRole("button", { name: "返回变更文件", exact: true }).click({
      timeout: timeout(deadline),
    });
    await shell.locator(REVIEW_TREE).waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  }
  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  const before = await page.evaluate(() => ({
    starts: Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_file_diff?.start ?? 0),
    rejected: Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_file_diff?.rejected ?? 0),
    calls: (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? []).length,
  }));
  await search.fill("conflict.ts");
  const conflictRow = shell.locator(REVIEW_FILE).filter({ hasText: "conflict.ts" });
  await conflictRow.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await conflictRow.count(), 1, "fixture 的 unmerged conflict 行必须唯一");
  const identity = await fileIdentity(conflictRow);
  await conflictRow.click({ timeout: timeout(deadline) });
  const message = "此文件有合并冲突，暂不支持显示组合 Diff。请解决冲突后重新读取。";
  await shell.getByText(message, { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const refresh = shell.getByRole("button", { name: "重新读取审阅", exact: true });
  await refresh.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await refresh.isEnabled(), true, "冲突诊断后仍须保留工具栏手动重读");
  await page.waitForTimeout(Math.min(750, timeout(deadline)));
  const result = await page.evaluate((callStart) => {
    const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
    const counts = globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_file_diff ?? {};
    return {
      calls: calls.slice(callStart).filter((call) => call.command === "ja_review_file_diff"),
      starts: Number(counts.start ?? 0),
      rejected: Number(counts.rejected ?? 0),
    };
  }, before.calls);
  assert.equal(result.calls.length, 1, "冲突 Diff 失败后不得自动重试");
  assert.equal(result.calls[0].phase, "rejected");
  assert.equal(result.calls[0].fileId, identity.fileId);
  assert.equal(result.calls[0].errorCode, "PARSE_ERROR");
  assert.equal(result.starts - before.starts, 1);
  assert.equal(result.rejected - before.rejected, 1);
  if ((await shell.getAttribute("data-review-layout")) === "narrow") {
    await shell.getByRole("button", { name: "返回变更文件", exact: true }).click({
      timeout: timeout(deadline),
    });
    await shell.locator(REVIEW_TREE).waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  }
  await search.fill("");
  return {
    diagnosticVisible: true,
    wireErrorCode: "PARSE_ERROR",
    domainErrorCode: "PARSE",
    automaticRetries: 0,
    manualRefreshAvailable: true,
  };
}

/** 等待 React 提交分组展开状态，并按稳定行身份读取，避免同步读取或动态 locator 抢跑。 */
async function waitForReviewRowExpansion(page, rowId, value, deadline) {
  await page.waitForFunction(
    ({ selector, identity, expanded }) => {
      const row = [...globalThis.document.querySelectorAll(selector)].find(
        (candidate) => candidate.getAttribute("data-row-id") === identity,
      );
      return row?.querySelector("[aria-expanded]")?.getAttribute("aria-expanded") === expanded;
    },
    { selector: "[data-row-id]", identity: rowId, expanded: value },
    { timeout: timeout(deadline) },
  );
}

/** 搜索期间允许临时展开祖先，清空后必须恢复用户先前折叠状态。 */
async function verifySearchRestoresExpansion(page, shell, deadline) {
  const group = shell.locator(`${REVIEW_GROUP}[aria-expanded="true"]`).first();
  await group.waitFor({ state: "visible", timeout: timeout(deadline) });
  const groupRow = group.locator("xpath=..");
  const rowId = await groupRow.getAttribute("data-row-id");
  assert.ok(rowId, "搜索恢复验收缺少稳定 group row identity");
  await group.click({ timeout: timeout(deadline) });
  await waitForReviewRowExpansion(page, rowId, "false", deadline);
  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  await search.fill("created.ts");
  await shell
    .locator(REVIEW_FILE)
    .filter({ hasText: "created.ts" })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await search.fill("");
  await waitForReviewRowExpansion(page, rowId, "false", deadline);
  return { restored: true, groupIdentity: rowId };
}

/** Review 树用紧凑图标按钮承载 Portal radio menu，按真实可访问控件切换分组。 */
async function chooseReviewGrouping(page, shell, optionName, deadline) {
  await shell.getByRole("button", { name: /^文件分组：/u }).click({
    timeout: timeout(deadline),
  });
  const menu = page.locator("[role='menu'][data-state='open']").last();
  await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
  await menu.getByRole("menuitemradio", { name: optionName, exact: true }).click({
    timeout: timeout(deadline),
  });
}

/** 三种分组经真实 radio menu 切换；选择身份在投影变化期间不得被清空。 */
async function verifyGroupingModes(page, shell, deadline) {
  const modes = ["目录", "平铺", "状态与目录"];
  const selectedDiff = shell.locator("[data-review-selected-file-id][data-review-selected-layer]");
  const selectedBefore = await selectedDiff.getAttribute("data-review-selected-file-id");
  assert.ok(selectedBefore, "切换分组前缺少所选文件 identity");
  for (const mode of modes) {
    await chooseReviewGrouping(page, shell, mode, deadline);
    assert.equal(await selectedDiff.getAttribute("data-review-selected-file-id"), selectedBefore);
  }
  await shell.getByRole("button", { name: "全部折叠", exact: true }).click({
    timeout: timeout(deadline),
  });
  const groups = await shell
    .locator(REVIEW_GROUP)
    .evaluateAll((nodes) =>
      nodes.map((node) => node.getAttribute("data-review-group")).filter(Boolean),
    );
  for (const required of ["conflicted", "unstaged", "staged", "untracked"]) {
    assert.ok(groups.includes(required), `状态分组缺少 ${required}`);
  }
  return { modes, groups };
}

/**
 * 通过 Workbench 外层真实 pointer resize 调整 Review content box；最多三次几何校正，禁止直接写
 * CSS variable 冒充用户拖动。外框尺寸只用于报告，严格 760px 断点按 content box 判定。
 */
async function resizeReviewContainer(page, shell, targetContentWidth, deadline) {
  const handle = page.getByRole("separator", { name: "调整工作台宽度", exact: true });
  await handle.waitFor({ state: "visible", timeout: timeout(deadline) });
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const shellBox = await readReviewShellWidths(shell);
    const handleBox = await handle.boundingBox();
    if (handleBox === null) throw new Error("Review resize 几何不可用");
    const widthMatches = Math.abs(shellBox.contentWidth - targetContentWidth) <= 1.5;
    const layoutMatches =
      reviewLayoutForContentWidth(shellBox.contentWidth) ===
      reviewLayoutForContentWidth(targetContentWidth);
    if (widthMatches && layoutMatches) break;
    const delta = shellBox.contentWidth - targetContentWidth;
    const startX = handleBox.x + handleBox.width / 2;
    const y = handleBox.y + handleBox.height / 2;
    await page.mouse.move(startX, y);
    await page.mouse.down();
    await page.mouse.move(startX + delta, y, { steps: 6 });
    await page.mouse.up();
  }
  const box = await readReviewShellWidths(shell);
  const expectedLayout = reviewLayoutForContentWidth(targetContentWidth);
  if (
    Math.abs(box.contentWidth - targetContentWidth) > REVIEW_WIDTH_TOLERANCE ||
    reviewLayoutForContentWidth(box.contentWidth) !== expectedLayout
  ) {
    throw new Error(
      `Review content 宽度未收敛：requested=${targetContentWidth} actual=${box.contentWidth} outer=${box.outerWidth}`,
    );
  }
  await page.waitForFunction(
    ({ selector, expected }) =>
      globalThis.document
        .querySelector(`.ja-thread-workbench-session:not([hidden]) .ja-workbench ${selector}`)
        ?.getAttribute("data-review-layout") === expected,
    { selector: REVIEW_SHELL, expected: expectedLayout },
    { timeout: timeout(deadline) },
  );
  return {
    requestedContentWidth: targetContentWidth,
    actualContentWidth: box.contentWidth,
    actualOuterWidth: box.outerWidth,
    layout: expectedLayout,
  };
}

/** 主题、palette 和减少动效均通过真实 Settings 控件应用，不直接写 DOM 属性。 */
async function applyAppearance(page, frame, deadline) {
  await page.emulateMedia({
    colorScheme: frame.systemColorScheme,
    reducedMotion: frame.reducedMotion ? "reduce" : "no-preference",
  });
  await page
    .getByRole("button", { name: "设置", exact: true })
    .click({ timeout: timeout(deadline) });
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.waitFor({ state: "visible", timeout: timeout(deadline) });
  await settings
    .getByRole("tab", { name: "外观", exact: true })
    .click({ timeout: timeout(deadline) });
  const themeOption =
    frame.theme === "light" ? "浅色" : frame.theme === "dark" ? "深色" : "跟随系统";
  await chooseSelectOption(
    page,
    settings.getByRole("combobox", { name: "外观模式", exact: true }),
    themeOption,
    deadline,
  );
  const motion = settings.getByRole("switch", { name: "减少动效", exact: true });
  const checked = (await motion.getAttribute("aria-checked")) === "true";
  if (checked !== frame.reducedMotion) await motion.click({ timeout: timeout(deadline) });
  if (typeof frame.palette === "string") {
    await chooseSelectOption(
      page,
      settings.getByRole("combobox", { name: "配色主题", exact: true }),
      frame.paletteLabel,
      deadline,
    );
  }
  await page.waitForFunction(
    ({ mode, reduced, palette }) => {
      const root = globalThis.document.documentElement;
      return (
        root.getAttribute("data-theme-mode") === mode &&
        root.getAttribute("data-reduce-motion") === String(reduced) &&
        globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches === reduced &&
        (palette === undefined || root.getAttribute("data-palette") === palette)
      );
    },
    { mode: frame.theme, reduced: frame.reducedMotion, palette: frame.palette },
    { timeout: timeout(deadline) },
  );
  await settings.getByRole("button", { name: "返回应用", exact: true }).click({
    timeout: timeout(deadline),
  });
  const workbench = await ensureCurrentWorkbench(page, deadline);
  const shell = workbench.locator(REVIEW_SHELL);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  return shell;
}

/** 采集矩阵前，窄栏若重挂载回到选中文件详情，先通过真实返回按钮恢复文件树再验布局。 */
async function captureMatrixFrame(page, client, shell, frame, evidenceDirectory, deadline) {
  await client.send("Emulation.setDeviceMetricsOverride", {
    width: frame.viewportWidth,
    height: frame.viewportHeight,
    deviceScaleFactor: frame.devicePixelRatio,
    mobile: false,
  });
  await page.waitForFunction(
    ({ width, ratio }) =>
      globalThis.innerWidth === width && Math.abs(globalThis.devicePixelRatio - ratio) <= 0.000001,
    { width: frame.viewportWidth, ratio: frame.devicePixelRatio },
    { timeout: timeout(deadline) },
  );
  shell = await applyAppearance(page, frame, deadline);
  const geometry = await resizeReviewContainer(page, shell, frame.width, deadline);
  const tree = shell.locator(REVIEW_TREE);
  if (!(await tree.isVisible())) {
    const backToFiles = shell.getByRole("button", { name: "返回变更文件", exact: true });
    await backToFiles.waitFor({ state: "visible", timeout: timeout(deadline) });
    await backToFiles.click({ timeout: timeout(deadline) });
  }
  await tree.waitFor({ state: "visible", timeout: timeout(deadline) });
  await tree.locator('[role="treeitem"]').first().focus();
  await tree.locator('[role="treeitem"]').first().press("ArrowDown");
  const facts = await shell.evaluate((node, expected) => {
    const root = globalThis.document.documentElement;
    const visible = (element) => {
      const style = globalThis.getComputedStyle(element);
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        element.getClientRects().length > 0
      );
    };
    const referencedText = (element) =>
      (element.getAttribute("aria-labelledby") ?? "")
        .split(/\s+/u)
        .filter(Boolean)
        .map((id) => globalThis.document.getElementById(id)?.textContent?.trim() ?? "")
        .join(" ")
        .trim();
    const accessibleName = (element) => {
      const labels =
        "labels" in element && element.labels !== null
          ? [...element.labels]
              .map((label) => label.textContent?.trim() ?? "")
              .join(" ")
              .trim()
          : "";
      return (
        element.getAttribute("aria-label")?.trim() ||
        referencedText(element) ||
        labels ||
        element.textContent?.trim() ||
        element.getAttribute("title")?.trim() ||
        ""
      );
    };
    const unnamed = [
      ...node.querySelectorAll(
        "button, input, select, textarea, [role='button'], [role='combobox']",
      ),
    ].filter((element) => visible(element) && accessibleName(element).length === 0).length;
    return {
      themeMode: root.getAttribute("data-theme-mode"),
      theme: root.getAttribute("data-theme"),
      reducedMotion: root.getAttribute("data-reduce-motion") === "true",
      mediaReducedMotion: globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches,
      devicePixelRatio: globalThis.devicePixelRatio,
      horizontalOverflow: node.scrollWidth - node.clientWidth,
      documentOverflow:
        globalThis.document.documentElement.scrollWidth -
        globalThis.document.documentElement.clientWidth,
      unnamedInteractiveCount: unnamed,
      dpiEvidence: "cdp_emulated",
      expected,
    };
  }, frame);
  assert.equal(facts.themeMode, frame.theme);
  assert.equal(facts.reducedMotion, frame.reducedMotion);
  assert.equal(facts.mediaReducedMotion, frame.reducedMotion);
  assert.ok(Math.abs(facts.devicePixelRatio - frame.devicePixelRatio) <= 0.000001);
  assert.ok(facts.horizontalOverflow <= 1, "Review 容器存在水平溢出");
  assert.ok(facts.documentOverflow <= 1, "文档存在水平溢出");
  assert.equal(facts.unnamedInteractiveCount, 0);
  const compactHeader = await verifyCompactHeader(shell);
  const fileName = `${frame.width}-${frame.theme}-dpi${String(frame.devicePixelRatio).replace(".", "_")}-${frame.reducedMotion ? "reduced" : "normal"}.png`;
  await shell.screenshot({ path: join(evidenceDirectory, fileName) });
  return { ...geometry, ...facts, compactHeader, screenshot: fileName };
}

/** 这两组矩阵检查可修复的当前报告；先等 Git 重载恢复动作，再检查普通文档的定位→修复顺序。 */
async function verifyCurrentAiFindingActions(shell, deadline) {
  const finding = shell.locator(".ja-review-ai-finding").first();
  const locate = finding.getByRole("button", { name: "定位", exact: true });
  const repair = finding.getByRole("button", { name: "修复", exact: true });
  await repair.click({ trial: true, timeout: timeout(deadline) });
  await locate.focus();
  await locate.press("Tab");
  assert.equal(
    await repair.evaluate((node) => node === globalThis.document.activeElement),
    true,
    "当前报告的定位→修复 Tab 顺序不正确",
  );
  const title = await finding
    .locator(".ja-review-ai-finding-title > span:last-child")
    .evaluate((node) => ({
      width: node.getBoundingClientRect().width,
      height: node.getBoundingClientRect().height,
      fontSize: Number.parseFloat(globalThis.getComputedStyle(node).fontSize),
      characters: Array.from(node.textContent ?? "").length,
    }));
  assert.ok(
    title.width >= Math.min(3, title.characters) * title.fontSize * 0.5,
    "AI finding 标题被挤成单字符窄列",
  );
  return title;
}

/** 窄矩阵等待真实修复动作恢复，避免把加载中的只读状态误当作报告焦点陷阱。 */
export async function captureCodingReviewAiNarrowMatrix(
  page,
  evidenceDirectory,
  deadlineMs = 8 * 60_000,
) {
  const deadline = Date.now() + deadlineMs;
  const workbench = await ensureCurrentWorkbench(page, deadline);
  let shell = workbench.locator(REVIEW_SHELL);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  const reportPanel = shell.locator(".ja-review-ai-panel");
  await reportPanel.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await reportPanel.locator(".ja-review-ai-finding").count()) === 0) {
    throw new Error("窄窗矩阵需要一条已载入的 AI Review finding");
  }

  const client = await page.context().newCDPSession(page);
  const frames = [];
  try {
    for (const frame of CODING_REVIEW_AI_NARROW_MATRIX) {
      await client.send("Emulation.setDeviceMetricsOverride", {
        width: frame.viewportWidth,
        height: frame.viewportHeight,
        deviceScaleFactor: frame.devicePixelRatio,
        mobile: false,
      });
      await page.waitForFunction(
        ({ width, ratio }) =>
          globalThis.innerWidth === width &&
          Math.abs(globalThis.devicePixelRatio - ratio) <= 0.000001,
        { width: frame.viewportWidth, ratio: frame.devicePixelRatio },
        { timeout: timeout(deadline) },
      );
      shell = await applyAppearance(page, frame, deadline);
      const geometry = await resizeReviewContainer(page, shell, frame.width, deadline);
      const findingTitle = await verifyCurrentAiFindingActions(shell, deadline);
      const facts = await shell.evaluate((node, expected) => {
        const root = globalThis.document.documentElement;
        const visible = (element) => {
          const style = globalThis.getComputedStyle(element);
          return (
            style.display !== "none" &&
            style.visibility !== "hidden" &&
            element.getClientRects().length > 0
          );
        };
        const overlaps = (selectors) => {
          const blocks = selectors.flatMap((selector) =>
            [...node.querySelectorAll(selector)].filter(visible).map((element) => ({
              selector,
              rect: element.getBoundingClientRect(),
            })),
          );
          const collisions = [];
          for (let left = 0; left < blocks.length; left += 1) {
            for (let right = left + 1; right < blocks.length; right += 1) {
              const a = blocks[left];
              const b = blocks[right];
              const width =
                Math.min(a.rect.right, b.rect.right) - Math.max(a.rect.left, b.rect.left);
              const height =
                Math.min(a.rect.bottom, b.rect.bottom) - Math.max(a.rect.top, b.rect.top);
              if (width > 1 && height > 1) collisions.push([a.selector, b.selector]);
            }
          }
          return collisions;
        };
        const active = globalThis.document.activeElement;
        const report = node.querySelector(".ja-review-ai-panel");
        const alerts = [...node.querySelectorAll('[role="alert"]')].filter(visible);
        return {
          themeMode: root.getAttribute("data-theme-mode"),
          devicePixelRatio: globalThis.devicePixelRatio,
          layout: node.getAttribute("data-review-layout"),
          horizontalOverflow: node.scrollWidth - node.clientWidth,
          documentOverflow:
            globalThis.document.documentElement.scrollWidth -
            globalThis.document.documentElement.clientWidth,
          overlapPairs: [
            ...overlaps([
              ".ja-review-ai-panel-header",
              ".ja-review-ai-summary",
              ".ja-review-ai-coverage",
              ".ja-review-ai-findings",
            ]),
            ...overlaps([
              ".ja-review-ai-finding-title",
              ".ja-review-ai-finding-location",
              ".ja-review-ai-finding-actions",
              ".ja-review-ai-finding-body",
              ".ja-review-ai-finding-evidence",
            ]),
          ],
          keyboardFocus: {
            insideReport: report !== null && active !== null && report.contains(active),
            visible: active instanceof globalThis.HTMLElement && active.matches(":focus-visible"),
            label:
              active?.getAttribute("aria-label") || active?.textContent?.trim().slice(0, 80) || "",
          },
          visibleErrorCount: alerts.length,
          deviceScaleFactorEvidence: "cdp_emulated",
          expected,
        };
      }, frame);
      assert.equal(facts.themeMode, frame.theme);
      assert.equal(facts.layout, "narrow");
      assert.ok(Math.abs(facts.devicePixelRatio - frame.devicePixelRatio) <= 0.000001);
      assert.ok(facts.horizontalOverflow <= 1, "AI Review 面板存在水平溢出");
      assert.ok(facts.documentOverflow <= 1, "AI Review 页面存在水平溢出");
      assert.deepEqual(facts.overlapPairs, [], "AI Review 内容存在几何重叠");
      assert.equal(facts.keyboardFocus.insideReport, true, "Tab 焦点离开 AI Review 报告");
      assert.equal(facts.keyboardFocus.visible, true, "键盘焦点未显示 focus-visible 样式");
      assert.equal(facts.visibleErrorCount, 0, "AI Review 报告仍显示错误");
      assert.equal(facts.deviceScaleFactorEvidence, "cdp_emulated");
      const diffReadiness = await waitForCodingReviewDiffReadiness(page, deadline);
      const fileName = `ai-review-narrow-${frame.theme}-dpr${String(frame.devicePixelRatio).replace(".", "_")}.png`;
      await shell.screenshot({ path: join(evidenceDirectory, fileName), animations: "disabled" });
      frames.push({ ...geometry, ...facts, diffReadiness, findingTitle, screenshot: fileName });
    }
  } finally {
    await client.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    await client.detach().catch(() => undefined);
  }
  return frames;
}

/** 每帧沿用已验证的宽窄视口，满足工作台的真实尺寸边界；CDP 视口在结束后恢复，不改应用限制。 */
export async function captureCodingReviewAiPaletteMatrix(
  page,
  evidenceDirectory,
  deadlineMs = 8 * 60_000,
) {
  const deadline = Date.now() + deadlineMs;
  const workbench = await ensureCurrentWorkbench(page, deadline);
  let shell = workbench.locator(REVIEW_SHELL);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  const reportPanel = shell.locator(".ja-review-ai-panel");
  await reportPanel.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await reportPanel.locator(".ja-review-ai-finding").count()) === 0) {
    throw new Error("palette 矩阵需要一条已载入的 AI Review finding");
  }

  const frames = [];
  const client = await page.context().newCDPSession(page);
  try {
    const combinations = new Map();
    for (const frame of CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX) {
      const key = `${frame.theme}/${frame.palette}`;
      if (!combinations.has(key)) combinations.set(key, frame);
    }
    for (const combination of combinations.values()) {
      shell = await applyAppearance(page, combination, deadline);
      const layoutFrames = CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX.filter(
        (frame) => frame.theme === combination.theme && frame.palette === combination.palette,
      );
      for (const frame of layoutFrames) {
        const viewportWidth = frame.width <= 520 ? 1280 : 1600;
        await client.send("Emulation.setDeviceMetricsOverride", {
          width: viewportWidth,
          height: 900,
          deviceScaleFactor: 1,
          mobile: false,
        });
        await page.waitForFunction(
          (width) =>
            globalThis.innerWidth === width &&
            Math.abs(globalThis.devicePixelRatio - 1) <= 0.000001,
          viewportWidth,
          { timeout: timeout(deadline) },
        );
        const geometry = await resizeReviewContainer(page, shell, frame.width, deadline);
        const findingTitle = await verifyCurrentAiFindingActions(shell, deadline);
        const facts = await shell.evaluate((node) => {
          const root = globalThis.document.documentElement;
          const visible = (element) => {
            const style = globalThis.getComputedStyle(element);
            return (
              style.display !== "none" &&
              style.visibility !== "hidden" &&
              element.getClientRects().length > 0
            );
          };
          const overlaps = (selectors) => {
            const blocks = selectors.flatMap((selector) =>
              [...node.querySelectorAll(selector)].filter(visible).map((element) => ({
                selector,
                rect: element.getBoundingClientRect(),
              })),
            );
            const collisions = [];
            for (let left = 0; left < blocks.length; left += 1) {
              for (let right = left + 1; right < blocks.length; right += 1) {
                const a = blocks[left];
                const b = blocks[right];
                const width =
                  Math.min(a.rect.right, b.rect.right) - Math.max(a.rect.left, b.rect.left);
                const height =
                  Math.min(a.rect.bottom, b.rect.bottom) - Math.max(a.rect.top, b.rect.top);
                if (width > 1 && height > 1) collisions.push([a.selector, b.selector]);
              }
            }
            return collisions;
          };
          const active = globalThis.document.activeElement;
          const report = node.querySelector(".ja-review-ai-panel");
          return {
            themeMode: root.getAttribute("data-theme-mode"),
            palette: root.getAttribute("data-palette"),
            devicePixelRatio: globalThis.devicePixelRatio,
            deviceScaleFactorEvidence: "cdp_emulated",
            layout: node.getAttribute("data-review-layout"),
            horizontalOverflow: node.scrollWidth - node.clientWidth,
            documentOverflow:
              globalThis.document.documentElement.scrollWidth -
              globalThis.document.documentElement.clientWidth,
            overlapPairs: [
              ...overlaps([
                ".ja-review-ai-panel-header",
                ".ja-review-ai-summary",
                ".ja-review-ai-coverage",
                ".ja-review-ai-findings",
              ]),
              ...overlaps([
                ".ja-review-ai-finding-title",
                ".ja-review-ai-finding-location",
                ".ja-review-ai-finding-actions",
                ".ja-review-ai-finding-body",
                ".ja-review-ai-finding-evidence",
              ]),
            ],
            keyboardFocus: {
              insideReport: report !== null && active !== null && report.contains(active),
              visible: active instanceof globalThis.HTMLElement && active.matches(":focus-visible"),
            },
            visibleErrorCount: [...node.querySelectorAll('[role="alert"]')].filter(visible).length,
          };
        });
        assert.equal(facts.themeMode, frame.theme);
        assert.equal(facts.palette, frame.palette);
        assert.equal(facts.layout, frame.layout);
        assert.ok(facts.horizontalOverflow <= 1, "AI Review 面板存在水平溢出");
        assert.ok(facts.documentOverflow <= 1, "AI Review 页面存在水平溢出");
        assert.deepEqual(facts.overlapPairs, [], "AI Review 内容存在几何重叠");
        assert.equal(facts.keyboardFocus.insideReport, true, "Tab 焦点离开 AI Review 报告");
        assert.equal(facts.keyboardFocus.visible, true, "键盘焦点未显示 focus-visible 样式");
        assert.equal(facts.visibleErrorCount, 0, "AI Review 报告仍显示错误");
        const diffReadiness = await waitForCodingReviewDiffReadiness(page, deadline);
        const fileName = `ai-review-palette-${frame.theme}-${frame.palette}-${frame.layout}.png`;
        await shell.screenshot({ path: join(evidenceDirectory, fileName), animations: "disabled" });
        frames.push({
          ...geometry,
          ...facts,
          diffReadiness,
          viewportWidth,
          findingTitle,
          screenshot: fileName,
        });
      }
    }
    assert.equal(frames.length, CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX.length);
    return frames;
  } finally {
    await client.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    await client.detach().catch(() => undefined);
  }
}

/** 读取真实布局几何，保证工具收敛后窄栏与主题变化仍保持一行且控件可见。 */
async function verifyCompactHeader(shell) {
  const facts = await shell.evaluate((node) => {
    const header = node.querySelector(".ja-review-shell-header");
    const rect = header.getBoundingClientRect();
    const buttons = [...header.querySelectorAll("button")].filter(
      (button) => button.getClientRects().length > 0,
    );
    const boxes = buttons.map((button) => button.getBoundingClientRect());
    return {
      height: rect.height,
      controlsSingleRow: boxes.every(
        (box) => Math.abs(box.top + box.height / 2 - (boxes[0].top + boxes[0].height / 2)) < 1,
      ),
      controlsWithinHeader: boxes.every(
        (box) => box.left >= rect.left && box.right <= rect.right && box.width >= 28,
      ),
      secondaryToolbarAbsent: node.querySelector(".ja-review-shell-toolbar") === null,
      historySelectorAbsent:
        node.querySelector('[role="combobox"][aria-label="审查记录"]') === null,
    };
  });
  assert.ok(facts.height <= 48, "审查顶栏高度超出单行预算");
  assert.equal(facts.controlsSingleRow, true, "审查控件必须同一行");
  assert.equal(facts.controlsWithinHeader, true, "顶栏控件不得被裁切或压缩");
  assert.equal(facts.secondaryToolbarAbsent, true);
  assert.equal(facts.historySelectorAbsent, true);
  return facts;
}

/** 不只点击布局按钮，还验证真实双栏、单列行号与零额外读取，防止静默回退掩盖失效。 */
async function verifyWideControls(page, shell, deadline, evidenceDirectory) {
  const resizer = shell.locator('[data-ja-review-tree-resizer][aria-label="调整文件树宽度"]');
  await resizer.waitFor({ state: "visible", timeout: timeout(deadline) });
  const before = await resizer.getAttribute("aria-valuenow");
  await resizer.focus();
  await resizer.press("ArrowLeft");
  const after = await resizer.getAttribute("aria-valuenow");
  assert.notEqual(after, before, "文件树键盘 resize 未改变宽度");
  const more = shell.getByRole("button", { name: "审查更多操作", exact: true });
  await more.click({ timeout: timeout(deadline) });
  for (const name of ["上一个文件", "下一个文件"])
    assert.equal(
      await page.getByRole("menuitem", { name, exact: true }).count(),
      1,
      `缺少 ${name} 控件`,
    );
  for (const name of ["双栏 Diff", "统一 Diff"])
    assert.equal(
      await page.getByRole("menuitemradio", { name, exact: true }).count(),
      1,
      `缺少 ${name} 控件`,
    );
  await page.keyboard.press("Escape");
  assert.equal(
    await more.evaluate((button) => button.ownerDocument.activeElement === button),
    true,
    "关闭更多必须返回入口焦点",
  );
  const countsBefore = await reviewInvokeCounts(page);
  await more.click({ timeout: timeout(deadline) });
  await page
    .getByRole("menuitemradio", { name: "双栏 Diff", exact: true })
    .click({ timeout: timeout(deadline) });
  const split = shell.locator('[data-review-diff-mode="split"]');
  await split.waitFor({ state: "visible", timeout: timeout(deadline) });
  const oldCell = split.locator('[data-review-diff-side="old"]').first();
  const newCell = split.locator('[data-review-diff-side="new"]').first();
  await oldCell.waitFor({ state: "visible", timeout: timeout(deadline) });
  const oldBox = await oldCell.boundingBox();
  const newBox = await newCell.boundingBox();
  assert.ok(oldBox && newBox && oldBox.x + oldBox.width <= newBox.x + 1, "双栏没有左右排列");
  await split.screenshot({ path: join(evidenceDirectory, "layout-split.png") });
  await more.click({ timeout: timeout(deadline) });
  await page.getByRole("menuitemradio", { name: "统一 Diff", exact: true }).click({
    timeout: timeout(deadline),
  });
  const unified = shell.locator('[data-review-diff-mode="unified"]');
  await unified.waitFor({ state: "visible", timeout: timeout(deadline) });
  const numberCounts = await unified
    .locator(".is-line")
    .evaluateAll((rows) =>
      rows.map((row) => row.querySelectorAll(".ja-review-unified-diff-number").length),
    );
  assert.ok(
    numberCounts.length > 0 && numberCounts.every((count) => count === 1),
    "统一视图重复行号",
  );
  await unified.screenshot({ path: join(evidenceDirectory, "layout-unified.png") });
  const countsAfter = await reviewInvokeCounts(page);
  for (const command of ["ja_review_snapshot", "ja_review_file_diff"])
    assert.equal(
      invokeCount(countsAfter, command),
      invokeCount(countsBefore, command),
      "布局切换额外读取文件",
    );
  await more.click({ timeout: timeout(deadline) });
  await page.getByRole("menuitem", { name: "下一个文件", exact: true }).click({
    timeout: timeout(deadline),
  });
  return { treeResizeKeyboard: true, diffModes: ["split", "unified"], fileNavigation: true };
}

/**
 * 分组矩阵会先折叠整棵树；通过 fixture 的稳定文件名让真实搜索展开祖先，再验证窄栏树、Diff 与返回路径。
 */
async function verifyNarrowNavigation(page, shell, deadline) {
  assert.equal(await shell.getAttribute("data-review-layout"), "narrow");
  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  await search.fill("created.ts");
  const matchingFiles = shell.locator(REVIEW_FILE).filter({ hasText: "created.ts" });
  await matchingFiles.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await matchingFiles.count(), 1, "窄栏导航的 fixture 文件必须唯一");
  const file = matchingFiles.first();
  const identity = await fileIdentity(file);
  await file.click({ timeout: timeout(deadline) });
  await waitForSelectedIdentity(page, identity, deadline);
  const back = shell.getByRole("button", { name: "返回变更文件", exact: true });
  await back.waitFor({ state: "visible", timeout: timeout(deadline) });
  await back.click({ timeout: timeout(deadline) });
  await shell.locator(REVIEW_TREE).waitFor({ state: "visible", timeout: timeout(deadline) });
  await search.fill("");
  return { treeToDiffToTree: true };
}

/** 用真实 git status 证明页面操作没有把 fixture 偷换成 mock 数据。 */
async function captureGitFacts(workspaceRoot) {
  const { stdout } = await execFileAsync("git.exe", ["status", "--porcelain=v2", "--branch"], {
    cwd: workspaceRoot,
    windowsHide: true,
    maxBuffer: 2 * 1024 * 1024,
    timeout: 15_000,
  });
  return {
    repositoryObserved: stdout.includes("# branch.head"),
    conflictObserved: stdout.split(/\r?\n/u).some((line) => line.startsWith("u ")),
    statusRecordCount: stdout.split(/\r?\n/u).filter((line) => /^[12u?] /u.test(line)).length,
  };
}

/**
 * @author kongweiguang
 * 执行 Git Review 重设计真窗验收。无活动 Thread 时只经真实“新会话”入口准备空 Thread，不发送消息；
 * 侧栏选中态与可见 Workbench scope 是会话身份依据，空态不要求挂载 Timeline。afterGitReview 沿用本轮 deadline，
 * 供调用方在同一时间预算内完成依赖当前 Git 快照的后续验收；afterCatalogReady 只用于内部诊断，
 * 允许在真实来源目录就绪处收口，避免跳过矩阵的结果被误标为完整 Git/Review 验收。
 */
export async function runReviewRedesignWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  scope,
  prepareThread,
  afterGitReview,
  afterCatalogReady,
  verifyCatalogLoadingMenu = false,
  deadlineMs = 15 * 60_000,
  setStage = () => {},
  onFailureDiagnostic,
}) {
  assert.ok(page, "page is required");
  assert.ok(["git", "full"].includes(scope), "scope must be git or full");
  setStage("driver:initialize");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + deadlineMs;
  if (verifyCatalogLoadingMenu) {
    await page.context().addInitScript(installReviewCatalogTestGate);
  }
  if (afterCatalogReady !== undefined) {
    await page.context().addInitScript(installReviewResourceObserver);
  }
  await page.context().addInitScript(installReviewInvokeProbe);
  setStage("driver:reload");
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  setStage("driver:runtime_readiness");
  const runtimeReadiness = await waitForApplication(page, deadline);
  const hiddenBefore = await reviewInvokeCounts(page);
  setStage("driver:project_select");
  await selectFixtureProject(page, deadline);
  await page.waitForTimeout(750);
  const hiddenAfter = await reviewInvokeCounts(page);
  const hiddenSnapshotDelta =
    invokeCount(hiddenAfter, "ja_review_snapshot") -
    invokeCount(hiddenBefore, "ja_review_snapshot");
  assert.equal(hiddenSnapshotDelta, 0, "隐藏 Review 在项目切换时发起 snapshot");

  setStage("driver:thread_prepare");
  const customThreadPreparation = await prepareThread?.(page, deadline);
  const currentThreadPreparation = await prepareReviewThread(
    page,
    deadline,
    evidenceDirectory,
    setStage,
    onFailureDiagnostic,
  );
  const threadPreparation = customThreadPreparation ?? currentThreadPreparation;
  setStage("driver:review_open");
  let shell = await openReview(page, deadline, evidenceDirectory, onFailureDiagnostic);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  setStage("driver:source_catalog_menu");
  const catalogMenuReadiness = await chooseAllUncommitted(
    page,
    evidenceDirectory,
    deadline,
    verifyCatalogLoadingMenu,
  );
  await shell.locator(REVIEW_TREE).waitFor({ state: "visible", timeout: timeout(deadline) });

  if (afterCatalogReady !== undefined) {
    setStage("driver:catalog_ready_callback");
    const diagnostic = await afterCatalogReady(page, deadline);
    assert.equal(diagnostic?.scope, "source_retry_focus_diagnostic");
    assert.equal(diagnostic?.verdict, "DIAGNOSTIC_COMPLETE");
    return diagnostic;
  }

  setStage("driver:git_interactions");
  const layeredSelection = await verifyLayeredFileSelection(page, shell, deadline);
  const latestWins = await verifyLatestWinsSelection(page, shell, deadline, evidenceDirectory);
  const conflictDiagnostic = await verifyConflictDiagnostic(page, shell, deadline);
  const search = await verifySearchRestoresExpansion(page, shell, deadline);
  const grouping = await verifyGroupingModes(page, shell, deadline);
  const git = await captureGitFacts(workspaceRoot);
  assert.equal(git.repositoryObserved, true);
  assert.equal(git.conflictObserved, true);

  const matrix = [];
  const cdp = await page.context().newCDPSession(page);
  let narrowNavigation;
  let wideControls;
  setStage("driver:responsive_matrix");
  for (const frame of REVIEW_REDESIGN_MATRIX) {
    shell = page.locator(REVIEW_SHELL);
    const evidence = await captureMatrixFrame(page, cdp, shell, frame, evidenceDirectory, deadline);
    matrix.push(evidence);
    if (frame.width === 360 && narrowNavigation === undefined) {
      narrowNavigation = await verifyNarrowNavigation(page, shell, deadline);
    }
    if (frame.width === 1000 && wideControls === undefined) {
      wideControls = await verifyWideControls(page, shell, deadline, evidenceDirectory);
    }
  }

  setStage("driver:after_git_callback");
  await afterGitReview?.(page, deadline);
  const counts = await reviewInvokeCounts(page);
  assert.ok(invokeCount(counts, "ja_review_snapshot", "resolved") >= 1);
  assert.ok(invokeCount(counts, "ja_review_file_diff", "resolved") >= 2);
  const turnReviewVisible = (await page.locator("[data-turn-review-kind]").count()) > 0;
  const turnReviewStatus = turnReviewVisible ? "observed" : "not_verified";
  const report = {
    contractVersion: 1,
    runtime: "tauri_webview2",
    gitAdapter: "real_native",
    scope,
    runtimeReadiness,
    threadPreparation: threadPreparation ?? {
      outcome: "existing_thread",
      currentThreadIdentityVerified: true,
    },
    verdict: reviewRedesignVerdict(scope, turnReviewStatus),
    gitReview: {
      status: "passed",
      hiddenSnapshotDelta,
      catalogMenuReadiness,
      layeredSelection,
      latestWins,
      conflictDiagnostic,
      search,
      grouping,
      narrowNavigation,
      wideControls,
      git,
      invokeCounts: counts,
      matrix,
    },
    turnReview:
      turnReviewStatus === "observed"
        ? {
            status: turnReviewStatus,
            note: "requires dedicated Turn lifecycle report for full acceptance",
          }
        : { status: turnReviewStatus, reason: "no deterministic Turn fixture in this session" },
  };
  setStage("driver:report_validation");
  validateReviewRedesignReport(report, { requestedScope: scope });
  return report;
}

/**
 * 报告验证器不允许 Git-only 或仅可见 Turn surface 冒充完整 PASS；full 必须由后续专用 Turn
 * 生命周期报告扩展并将 turnReview.status 提升为 passed，CDP 比例只容忍支持倍率内的回读噪声。
 */
export function validateReviewRedesignReport(report, { requestedScope }) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.gitAdapter, "real_native");
  assert.equal(report?.scope, requestedScope);
  assert.equal(report?.runtimeReadiness?.command, "ja_runtime_state");
  assert.equal(report?.runtimeReadiness?.status, "ready");
  assert.ok(Number.isSafeInteger(report?.runtimeReadiness?.generation));
  assert.ok(report.runtimeReadiness.generation > 0);
  assert.equal(report?.runtimeReadiness?.serverInstanceIdPresent, true);
  assert.equal(report?.runtimeReadiness?.projectedReady, true);
  assert.equal(report?.gitReview?.status, "passed");
  assert.equal(report?.gitReview?.hiddenSnapshotDelta, 0);
  assert.equal(report?.gitReview?.matrix?.length, REVIEW_REDESIGN_MATRIX.length);
  const widths = new Set(
    report.gitReview.matrix.map(({ requestedContentWidth }) => requestedContentWidth),
  );
  const themes = new Set(report.gitReview.matrix.map(({ themeMode }) => themeMode));
  const ratios = new Set(
    report.gitReview.matrix.map(({ devicePixelRatio }) =>
      normalizeDevicePixelRatio(devicePixelRatio),
    ),
  );
  assert.ok(
    report.gitReview.matrix.every(
      ({ requestedContentWidth, actualContentWidth, actualOuterWidth, layout }) =>
        Number.isFinite(requestedContentWidth) &&
        Number.isFinite(actualContentWidth) &&
        Number.isFinite(actualOuterWidth) &&
        Math.abs(actualContentWidth - requestedContentWidth) <= REVIEW_WIDTH_TOLERANCE &&
        actualOuterWidth >= actualContentWidth &&
        layout === reviewLayoutForContentWidth(requestedContentWidth) &&
        layout === reviewLayoutForContentWidth(actualContentWidth),
    ),
    "Review matrix width evidence does not match the content-box breakpoint",
  );
  assert.deepEqual(widths, new Set([360, 520, 760, 1000]));
  assert.deepEqual(themes, new Set(["light", "dark", "system"]));
  assert.deepEqual(ratios, new Set([1, 1.25, 1.5]));
  assert.ok(report.gitReview.matrix.every(({ dpiEvidence }) => dpiEvidence === "cdp_emulated"));
  assert.ok(report.gitReview.matrix.some(({ reducedMotion }) => reducedMotion));
  if (requestedScope === "full") {
    assert.equal(report.turnReview?.status, "passed", "完整验收缺少 Turn lifecycle 证据");
    assert.equal(report.verdict, "PASS");
  } else {
    assert.notEqual(report.verdict, "PASS", "Git-only 不得冒充完整 PASS");
  }
  return report;
}
