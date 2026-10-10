// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Windows Tauri/WebView2 workspace 切换验收 runner。
 *
 * 该 runner 复用 review-redesign-production 的真实 JVM JAR、隔离 profile、WebView2
 * CDP/EdgeDriver 和进程清理边界，额外建立两个真实 Git workspace 与持久 Thread，
 * 通过 loopback Responses Provider 产生历史正文，再测量 A/B 热切换和滚动恢复。
 * baseline 阶段允许记录旧行为；after 阶段才对标题行 spinner、非空 Timeline、
 * 显式 Thread identity 和隐藏 Review snapshot 增量执行改后门禁。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { responseStream } from "./conversation-recovery-fixture.mjs";
import {
  createReviewGitFixture,
  parseArguments as parseProductionArguments,
  runProduction,
  validateEdgeDriver,
  validateToolchain,
} from "./review-redesign-production.mjs";
import { installReviewInvokeProbe } from "./review-redesign-webview2-driver.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_SAMPLES = 5;
const MIN_SAMPLES = 3;
const MAX_SAMPLES = 20;
const REQUIRED_IGNORED_FILES = 4_500;
const REQUIRED_UNTRACKED_FILES = 2_000;
const SWITCH_P95_BUDGET_MS = 2_000;
const HISTORY_TURNS = 4;
const PROJECT_LABELS = Object.freeze({ a: "切换验收 A", b: "切换验收 B" });
const HISTORY_LOADING_OBSERVATION_KEYS = Object.freeze([
  "busyEmpty",
  "busyWithRows",
  "idleEmpty",
  "idleWithRows",
  "unavailable",
]);
const HISTORY_LOADING_VIOLATION_KEYS = Object.freeze([
  "unavailableHistory",
  "invalidBusyValue",
  "missingExpectedSpinner",
  "invisibleExpectedSpinner",
  "misplacedExpectedSpinner",
  "multipleSpinners",
  "unexpectedSpinnerWhileCachedBusy",
  "unexpectedSpinnerWhileIdle",
]);
const THREAD_SEEN_FAILURE_CODES = Object.freeze([
  "CONFLICT",
  "THREAD_NOT_FOUND",
  "INVALID_STATE",
  "STORAGE_CONFLICT",
  "INVALID_PARAMS",
  "INVALID_INPUT",
  "RUNTIME_UNAVAILABLE",
  "RUNTIME_QUEUE_FULL",
  "RUNTIME_COMMAND_DEADLINE",
  "RUNTIME_SHUTDOWN_TIMEOUT",
  "RUNTIME_EVENT_DELIVERY_FAILED",
  "RECOVERY_REQUIRED",
  "RECOVERY_STALE",
  "RUNTIME_FAULTED",
  "RUNTIME_BACKOFF",
  "SHUTTING_DOWN",
  "RUNTIME_NOT_READY",
  "RUNTIME_TIMEOUT",
  "SIDECAR_CRASHED",
  "RUNTIME_PROTOCOL_ERROR",
  "PROTOCOL_INCOMPATIBLE",
  "REQUEST_DEADLINE_EXCEEDED",
]);
const THREAD_SEEN_REPORT_CODES = new Set([...THREAD_SEEN_FAILURE_CODES, "INVOKE_FAILED"]);
const THREAD_SEEN_TURN_STATES = new Set([
  "queued",
  "running",
  "waiting_input",
  "waiting_approval",
  "suspended",
  "completed",
  "failed",
  "cancelled",
]);

const BASE_NO_VALUE_ARGUMENTS = new Set(["--preflight-only", "--hidden-window"]);

/**
 * 合并生产 runner 的 launch/attach 参数与本 runner 的阶段参数；本验收必须由隔离 launch
 * 提供 loopback 配置，拒绝 attach 以免误连用户已有 Provider 或 profile。
 */
export function parseArguments(argv) {
  const baseArguments = [];
  let samples = DEFAULT_SAMPLES;
  let phase = "after";
  let baselineReport;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--samples" || argument === "--phase" || argument === "--baseline-report") {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith("--"))
        throw new Error(`missing value for ${argument}`);
      if (argument === "--samples") samples = Number(value);
      else if (argument === "--phase") phase = value;
      else baselineReport = resolve(value);
      index += 1;
      continue;
    }
    baseArguments.push(argument);
    if (!BASE_NO_VALUE_ARGUMENTS.has(argument)) {
      const value = argv[index + 1];
      if (value !== undefined && !value.startsWith("--")) {
        baseArguments.push(value);
        index += 1;
      }
    }
  }
  if (!baseArguments.includes("--scope")) baseArguments.push("--scope", "git");
  const production = parseProductionArguments(baseArguments);
  if (production.cdpEndpoint !== undefined)
    throw new Error("workspace switch runner requires an isolated launch, not attach mode");
  if (production.fixture !== "full")
    throw new Error("workspace switch runner requires the full Git pressure fixture");
  if (!Number.isSafeInteger(samples) || samples < MIN_SAMPLES || samples > MAX_SAMPLES) {
    throw new Error(`--samples must be an integer between ${MIN_SAMPLES} and ${MAX_SAMPLES}`);
  }
  if (!(["baseline", "after"].includes(phase)))
    throw new Error("--phase must be baseline or after");
  if (production.ignoredFiles < REQUIRED_IGNORED_FILES) {
    throw new Error(`--ignored-files must be at least ${REQUIRED_IGNORED_FILES}`);
  }
  if (production.untrackedFiles < REQUIRED_UNTRACKED_FILES) {
    throw new Error(`--untracked-files must be at least ${REQUIRED_UNTRACKED_FILES}`);
  }
  if (!baseArguments.includes("--cargo-target-directory")) {
    production.cargoTargetDirectory = join(repoRoot, "target", "codex-workspace-switch");
  }
  return { ...production, samples, phase, baselineReport };
}

/**
 * 使用 nearest-rank 计算小样本端到端分位数，确保 p50/p95 都对应真实观测样本，
 * 不用插值制造并未发生过的耗时。
 */
export function percentile(values, percentileValue) {
  assert.ok(values.length > 0, "percentile requires samples");
  assert.ok(percentileValue > 0 && percentileValue <= 1, "percentile must be within (0, 1]");
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * percentileValue) - 1)];
}

/**
 * 汇总实际 click 到目标正文出现的耗时；保留 raw samples 以便改前/改后在同一机器重算。
 */
export function summarize(values) {
  assert.ok(values.length > 0, "summarize requires samples");
  assert.ok(values.every((value) => Number.isFinite(value) && value >= 0));
  return {
    count: values.length,
    minMs: Math.min(...values),
    p50Ms: percentile(values, 0.5),
    p95Ms: percentile(values, 0.95),
    maxMs: Math.max(...values),
    samples: [...values],
  };
}

/**
 * @author kongweiguang
 * 只旁路观察正式 threadSeen 命令并原样串接既有 E2E probe；诊断仅保存 revision、闭集状态/错误码与警告计数。
 */
export function installThreadSeenInvokeProbe(knownFailureCodes) {
  const commandName = "ja_thread_seen";
  const warningText = "未读状态暂时无法同步，请重新打开会话重试。";
  const turnStates = new Set([
    "queued",
    "running",
    "waiting_input",
    "waiting_approval",
    "suspended",
    "completed",
    "failed",
    "cancelled",
  ]);
  const failureCodes = new Set([...knownFailureCodes, "INVOKE_FAILED"]);
  const diagnostics = {
    invocations: [],
    warningAppearanceCount: 0,
    warningEverSeen: false,
    warningVisible: false,
  };
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__ = diagnostics;
  /** 保留既有 wrapper 的唯一委托入口，不让观察器额外发起或重试命令。 */
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    /** 原样串接 Review probe，避免覆盖其调用计数和失败身份。 */
    const invokePrevious = () =>
      previous === undefined ? delegate() : previous(request, delegate);
    if (request?.command !== commandName) return invokePrevious();

    const expectedRevision = request?.args?.input?.expectedThreadRevision;
    const invocation = {
      ordinal: diagnostics.invocations.length + 1,
      expectedThreadRevision:
        Number.isSafeInteger(expectedRevision) && expectedRevision >= 0 ? expectedRevision : null,
      outcome: "pending",
    };
    diagnostics.invocations.push(invocation);
    try {
      const result = await invokePrevious();
      const resultRevision = result?.revision;
      const latestTurnStatus = result?.latestTurnStatus;
      const latestTurnSeen = result?.latestTurnSeen;
      invocation.outcome = "resolved";
      invocation.resultValid =
        Number.isSafeInteger(resultRevision) &&
        resultRevision >= 0 &&
        (latestTurnStatus === null || turnStates.has(latestTurnStatus)) &&
        typeof latestTurnSeen === "boolean";
      invocation.resultRevision =
        Number.isSafeInteger(resultRevision) && resultRevision >= 0 ? resultRevision : null;
      invocation.latestTurnStatus =
        latestTurnStatus === null || turnStates.has(latestTurnStatus) ? latestTurnStatus : null;
      invocation.latestTurnSeen =
        typeof latestTurnSeen === "boolean" ? latestTurnSeen : null;
      return result;
    } catch (error) {
      let code;
      try {
        code = error !== null && typeof error === "object" ? error.code : undefined;
      } catch {
        // 诊断读取器异常不能替换 bridge 原始拒绝对象。
      }
      invocation.outcome = "rejected";
      invocation.errorCode =
        typeof code === "string" && failureCodes.has(code) ? code : "INVOKE_FAILED";
      throw error;
    }
  };

  /** 排除离屏卸载和祖先隐藏节点，告警计数只对应用户可见的真实反馈。 */
  const isVisible = (element) => {
    if (
      element?.isConnected !== true ||
      typeof element.getClientRects !== "function" ||
      element.getClientRects().length === 0
    ) {
      return false;
    }
    for (
      let ancestor = element;
      ancestor !== null && ancestor !== undefined;
      ancestor = ancestor.parentElement
    ) {
      const style = globalThis.getComputedStyle(ancestor);
      if (
        style.display === "none" ||
        style.visibility === "hidden" ||
        style.visibility === "collapse" ||
        style.contentVisibility === "hidden" ||
        Number(style.opacity) === 0
      ) {
        return false;
      }
    }
    return true;
  };
  /** 只累计可见边沿，不把同一告警的每次 DOM 更新重复算作新故障。 */
  const observeWarning = () => {
    const visible = Array.from(
      globalThis.document.querySelectorAll(".ja-navigation-error, .ja-notification-content"),
    ).some(
      (element) =>
        element.getAttribute("role") === "alert" &&
        typeof element.textContent === "string" &&
        element.textContent.includes(warningText) &&
        isVisible(element),
    );
    if (visible && !diagnostics.warningVisible) {
      diagnostics.warningAppearanceCount += 1;
      diagnostics.warningEverSeen = true;
    }
    diagnostics.warningVisible = visible;
  };
  const observer = new globalThis.MutationObserver(observeWarning);
  /** 等待真实 DOM 根后一次安装，避免 init script 早于页面创建时漏掉告警。 */
  const startObserving = () => {
    if (globalThis.document.documentElement === null) return;
    observer.observe(globalThis.document.documentElement, {
      attributes: true,
      attributeFilter: [
        "aria-hidden",
        "class",
        "data-mounted",
        "data-removed",
        "data-state",
        "hidden",
        "role",
        "style",
      ],
      characterData: true,
      childList: true,
      subtree: true,
    });
    observeWarning();
  };
  if (globalThis.document.documentElement === null) {
    globalThis.document.addEventListener("DOMContentLoaded", startObserving, { once: true });
  } else {
    startObserving();
  }
}

/**
 * @author kongweiguang
 * Playwright 不保证多个 init script 的执行顺序；把现有 Review wrapper 与已读观察器组合成单一有序脚本，避免任一 wrapper 被覆盖。
 */
export function createWorkspaceSwitchInvokeProbeBootstrap() {
  return [
    `(${installReviewInvokeProbe.toString()})();`,
    `(${installThreadSeenInvokeProbe.toString()})(${JSON.stringify(THREAD_SEEN_FAILURE_CODES)});`,
  ].join("\n");
}

/**
 * @author kongweiguang
 * 仅在快速输入场景结束后等待已经发出的命令自然完成；不串行化每轮输入、不补发或重试。
 * Deadline 只结束观察，pending 仍交给最终报告拒绝，避免 reload 把正常在途请求误写成永久 pending。
 */
export async function waitForThreadSeenSettled(page, timeoutMs = 60_000) {
  try {
    await page.waitForFunction(
      () => {
        const diagnostics = globalThis.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__;
        return diagnostics === undefined ||
          !Array.isArray(diagnostics.invocations) ||
          diagnostics.invocations.every((invocation) => invocation.outcome !== "pending");
      },
      undefined,
      { timeout: timeoutMs, polling: 100 },
    );
    return true;
  } catch (error) {
    if (error?.name === "TimeoutError") return false;
    throw error;
  }
}

/**
 * @author kongweiguang
 * 只把浏览器 probe 的 allowlist 字段汇入宿主报告，并给跨 reload 记录重新编号，避免任何 Thread 标识或正文外流。
 */
async function collectThreadSeenDiagnostics(page, aggregate) {
  const current = await page.evaluate(() => {
    const diagnostics = globalThis.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__;
    if (diagnostics === undefined || !Array.isArray(diagnostics.invocations)) return null;
    return {
      invocations: diagnostics.invocations,
      warningAppearanceCount: diagnostics.warningAppearanceCount,
      warningEverSeen: diagnostics.warningEverSeen,
      warningVisible: diagnostics.warningVisible,
    };
  });
  if (current === null) {
    aggregate.probeInstalled = false;
    return;
  }

  aggregate.probeInstalled = aggregate.probeInstalled !== false;
  for (const source of current.invocations) {
    const invocation = {
      ordinal: aggregate.invocations.length + 1,
      expectedThreadRevision:
        Number.isSafeInteger(source?.expectedThreadRevision) && source.expectedThreadRevision >= 0
          ? source.expectedThreadRevision
          : null,
      outcome: ["pending", "resolved", "rejected"].includes(source?.outcome)
        ? source.outcome
        : "unknown",
    };
    if (source?.outcome === "resolved") {
      invocation.resultValid = source.resultValid === true;
      invocation.resultRevision =
        Number.isSafeInteger(source.resultRevision) && source.resultRevision >= 0
          ? source.resultRevision
          : null;
      invocation.latestTurnStatus =
        source.latestTurnStatus === null || THREAD_SEEN_TURN_STATES.has(source.latestTurnStatus)
          ? source.latestTurnStatus
          : null;
      invocation.latestTurnSeen = typeof source.latestTurnSeen === "boolean" ? source.latestTurnSeen : null;
    } else if (source?.outcome === "rejected") {
      invocation.errorCode = THREAD_SEEN_REPORT_CODES.has(source.errorCode)
        ? source.errorCode
        : "INVOKE_FAILED";
    }
    aggregate.invocations.push(invocation);
  }
  aggregate.warningAppearanceCount +=
    Number.isSafeInteger(current.warningAppearanceCount) && current.warningAppearanceCount >= 0
      ? current.warningAppearanceCount
      : -1;
  aggregate.warningEverSeen =
    typeof current.warningEverSeen === "boolean"
      ? aggregate.warningEverSeen === true || current.warningEverSeen
      : null;
  aggregate.warningVisibleAtEnd =
    typeof current.warningVisible === "boolean" ? current.warningVisible : null;
}

/**
 * @author kongweiguang
 * 压力 runner 只接受共享 launcher 实际捕获的桌面身份，避免有完整测量但无法证明运行二进制的报告通过。
 */
function assertOwnedTauriIdentity(identity) {
  assert.ok(Number.isSafeInteger(identity?.pid) && identity.pid > 0, "execution.tauri.pid is required");
  assert.ok(
    Number.isSafeInteger(identity?.launcherPid) &&
      identity.launcherPid > 0 &&
      identity.launcherPid !== identity.pid,
    "execution.tauri.launcherPid must identify its distinct owner",
  );
  assert.ok(
    typeof identity?.executablePath === "string" && isAbsolute(identity.executablePath),
    "execution.tauri.executablePath must be absolute",
  );
  assert.equal(
    basename(identity.executablePath).toLowerCase(),
    "ja-desktop.exe",
    "execution.tauri must identify the desktop host",
  );
  assert.match(identity?.sha256 ?? "", /^[0-9a-f]{64}$/u, "execution.tauri.sha256 must be lowercase SHA-256");
  assert.ok(
    Number.isSafeInteger(identity?.sizeBytes) && identity.sizeBytes > 0,
    `execution.tauri.sizeBytes=${identity?.sizeBytes}`,
  );
}

/**
 * @author kongweiguang
 * 按明确运行边界校验 JAR 或 Native sidecar 报告；Native 必须与共享启动层已验证的 hash、尺寸和无 fallback 事实闭合。
 * 共享 launch 回执必须同时给出实际 Tauri 后代身份；raw driver 结果尚未具备此身份，不可提前作为验收报告。
 * 未观察的 loading 分支保持零计数，不转换成通过标记。
 */
export function validateWorkspaceSwitchReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.ok(["baseline", "after"].includes(report?.phase), `phase=${report?.phase}`);
  if (Object.hasOwn(report, "verdict")) {
    assert.equal(report.verdict, "PASS", `verdict=${report.verdict}`);
  }
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.platform, "win32");
  assertOwnedTauriIdentity(report?.execution?.tauri);
  const nativeSidecar = report?.runtime?.boundary === "native_sidecar";
  assert.ok(
    nativeSidecar || report?.runtime?.boundary === "jvm_jar",
    `runtime.boundary=${report?.runtime?.boundary}`,
  );
  assert.equal(report?.runtime?.nativeImageVerified, nativeSidecar);
  assert.equal(report?.runtime?.toolchain?.nodeMajor, 24);
  assert.equal(report?.runtime?.toolchain?.pnpm, "10.33.0");
  if (nativeSidecar) {
    const image = report?.runtime?.nativeImage;
    assert.match(image?.sha256 ?? "", /^[0-9a-f]{64}$/u, "nativeImage.sha256 must be lowercase SHA-256");
    assert.ok(Number.isSafeInteger(image?.sizeBytes) && image.sizeBytes > 0, `nativeImage.sizeBytes=${image?.sizeBytes}`);
    assert.equal(image?.resourceName, "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe");
    assert.equal(image?.noFallback, true);
    assert.equal(image?.sourceHashMatched, true);
    assert.equal(report?.runtime?.toolchain?.jdkMajor, undefined);
    assert.equal(report?.runtime?.toolchain?.mavenJavaMajor, undefined);
    assert.ok(report?.execution, "native execution identity is required before report acceptance");
    assert.equal(report.execution.boundary, "native_sidecar");
    assert.equal(report.execution.nativeImageVerified, true);
    assert.deepEqual(report.execution.nativeImage, image, "runtime and shared Native identities differ");
    assert.equal(report.execution.jar, undefined, "Native mode must not fall back to a JAR");
  } else {
    assert.equal(report?.runtime?.nativeImage, undefined);
    assert.equal(report?.runtime?.toolchain?.jdkMajor, 25);
    assert.equal(report?.runtime?.toolchain?.mavenJavaMajor, 25);
    if (report?.execution !== undefined) {
      assert.equal(report.execution.boundary, "debug_jar");
      assert.equal(report.execution.nativeImageVerified, false);
      assert.equal(report.execution.nativeImage, undefined);
      assert.match(report.execution.jar?.sha256 ?? "", /^[0-9a-f]{64}$/u);
      assert.ok(Number.isSafeInteger(report.execution.jar?.size) && report.execution.jar.size > 0);
    }
  }
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.fixture?.projectCount, 2);
  assert.equal(report?.fixture?.ignoredFiles, REQUIRED_IGNORED_FILES);
  assert.equal(report?.fixture?.untrackedFiles, REQUIRED_UNTRACKED_FILES);
  const historyLoading = report?.correctness?.historyLoading;
  assert.deepEqual(
    Object.keys(historyLoading?.observations ?? {}).sort(),
    [...HISTORY_LOADING_OBSERVATION_KEYS].sort(),
  );
  assert.deepEqual(
    Object.keys(historyLoading?.violations ?? {}).sort(),
    [...HISTORY_LOADING_VIOLATION_KEYS].sort(),
  );
  for (const [branch, count] of Object.entries(historyLoading.observations)) {
    assert.ok(Number.isSafeInteger(count) && count >= 0, `historyLoading.observations.${branch}=${count}`);
  }
  for (const [violation, count] of Object.entries(historyLoading.violations)) {
    assert.ok(Number.isSafeInteger(count) && count >= 0, `historyLoading.violations.${violation}=${count}`);
  }
  assert.ok(
    Object.values(historyLoading.observations).some((count) => count > 0),
    "historyLoading observations missing",
  );
  assert.equal(report?.correctness?.hiddenReviewSnapshotDelta, 0);
  assert.equal(report?.correctness?.pageErrors, 0, `pageErrors=${report?.correctness?.pageErrors}`);
  const threadSeen = report?.correctness?.threadSeen;
  assert.deepEqual(
    Object.keys(threadSeen ?? {}).sort(),
    [
      "invocations",
      "probeInstalled",
      "warningAppearanceCount",
      "warningEverSeen",
      "warningVisibleAtEnd",
    ].sort(),
  );
  assert.equal(threadSeen.probeInstalled, true);
  assert.ok(Array.isArray(threadSeen.invocations));
  for (const [index, invocation] of threadSeen.invocations.entries()) {
    assert.equal(invocation.ordinal, index + 1, `threadSeen.invocations[${index}].ordinal`);
    assert.ok(
      Number.isSafeInteger(invocation.expectedThreadRevision) &&
        invocation.expectedThreadRevision >= 0,
      `threadSeen.invocations[${index}].expectedThreadRevision`,
    );
    const commonKeys = ["ordinal", "expectedThreadRevision", "outcome"];
    if (invocation.outcome === "pending") {
      assert.deepEqual(Object.keys(invocation).sort(), commonKeys.sort());
    } else if (invocation.outcome === "resolved") {
      assert.deepEqual(
        Object.keys(invocation).sort(),
        [
          ...commonKeys,
          "resultValid",
          "resultRevision",
          "latestTurnStatus",
          "latestTurnSeen",
        ].sort(),
      );
      assert.equal(invocation.resultValid, true);
      assert.ok(
        Number.isSafeInteger(invocation.resultRevision) && invocation.resultRevision >= 0,
        `threadSeen.invocations[${index}].resultRevision`,
      );
      assert.ok(
        invocation.latestTurnStatus === null ||
          THREAD_SEEN_TURN_STATES.has(invocation.latestTurnStatus),
        `threadSeen.invocations[${index}].latestTurnStatus`,
      );
      assert.equal(typeof invocation.latestTurnSeen, "boolean");
    } else if (invocation.outcome === "rejected") {
      assert.deepEqual(
        Object.keys(invocation).sort(),
        [...commonKeys, "errorCode"].sort(),
      );
      assert.ok(
        THREAD_SEEN_REPORT_CODES.has(invocation.errorCode),
        `threadSeen.invocations[${index}].errorCode`,
      );
    } else {
      assert.fail(`threadSeen.invocations[${index}].outcome=${invocation.outcome}`);
    }
  }
  assert.ok(
    Number.isSafeInteger(threadSeen.warningAppearanceCount) &&
      threadSeen.warningAppearanceCount >= 0,
    `threadSeen.warningAppearanceCount=${threadSeen.warningAppearanceCount}`,
  );
  assert.equal(typeof threadSeen.warningEverSeen, "boolean");
  assert.equal(typeof threadSeen.warningVisibleAtEnd, "boolean");
  assert.equal(threadSeen.warningEverSeen, threadSeen.warningAppearanceCount > 0);
  if (threadSeen.warningVisibleAtEnd) assert.equal(threadSeen.warningEverSeen, true);
  assert.equal(report?.correctness?.noBlankTimeline, true);
  assert.ok(Number.isInteger(report?.correctness?.blankFrameCount) && report.correctness.blankFrameCount >= 0, `blankFrameCount=${report?.correctness?.blankFrameCount}`);
  assert.ok(Number.isInteger(report?.correctness?.paintFrameCount) && report.correctness.paintFrameCount > 0, `paintFrameCount=${report?.correctness?.paintFrameCount}`);
  assert.equal(report?.correctness?.projectsScoped, true);
  assert.ok(report?.metrics?.aToB?.count >= MIN_SAMPLES);
  assert.ok(report?.metrics?.bToA?.count >= MIN_SAMPLES);
  assert.ok(Number.isFinite(report.metrics.aToB.p50Ms));
  assert.ok(Number.isFinite(report.metrics.aToB.p95Ms));
  assert.ok(Number.isFinite(report.metrics.bToA.p50Ms));
  assert.ok(Number.isFinite(report.metrics.bToA.p95Ms));
  if (report.phase === "after") {
    assert.ok(threadSeen.invocations.length > 0, "threadSeen probe did not observe a production command");
    assert.ok(
      threadSeen.invocations.every((invocation) => invocation.outcome !== "pending"),
      "threadSeen command was still pending when the after report was captured",
    );
    assert.equal(
      threadSeen.warningAppearanceCount,
      0,
      `threadSeen.warningAppearanceCount=${threadSeen.warningAppearanceCount}`,
    );
    assert.equal(threadSeen.warningEverSeen, false);
    assert.equal(threadSeen.warningVisibleAtEnd, false);
    assert.equal(report.correctness.spinnerLegacyPlaceholder, false);
    for (const [violation, count] of Object.entries(historyLoading.violations)) {
      assert.equal(count, 0, `historyLoading.violations.${violation}=${count}`);
    }
    assert.equal(report.correctness.explicitThreadIdentity, true);
    assert.equal(report.correctness.scrollRestored, true);
    assert.equal(report.correctness.blankFrameCount, 0, `blankFrameCount=${report.correctness.blankFrameCount}`);
    assert.ok(report.correctness.paintFrameCount > 0, `paintFrameCount=${report.correctness.paintFrameCount}`);
    assert.ok(report.metrics.aToB.p95Ms <= SWITCH_P95_BUDGET_MS);
    assert.ok(report.metrics.bToA.p95Ms <= SWITCH_P95_BUDGET_MS);
  } else {
    assert.equal(report.phase, "baseline");
  }
  assert.equal(report.baseline?.absoluteOnly, report.baseline?.available !== true);
  return report;
}

/**
 * @author kongweiguang
 * 驱动层拒绝已采样报告时保留原始部分证据，只追加失败身份，不为未执行的分支合成结果。
 */
export function workspaceSwitchFailureReport(report, error) {
  return {
    ...report,
    verdict: "FAIL",
    error: error?.name === "AssertionError" ? "report_validation_failed" : "runner_failed_after_measurement",
  };
}

/**
 * 读取同一 runner 产生的基线摘要，只接纳无路径、无正文依赖的 metrics 形状；基线读取
 * 是只读比较，不把旧报告当作当前产品的通过证据。
 */
export async function readBaselineReport(path) {
  const candidate = JSON.parse(await readFile(path, "utf8"));
  const aToB = candidate?.metrics?.aToB;
  const bToA = candidate?.metrics?.bToA;
  if (
    !Number.isFinite(aToB?.p50Ms) ||
    !Number.isFinite(aToB?.p95Ms) ||
    !Number.isFinite(bToA?.p50Ms) ||
    !Number.isFinite(bToA?.p95Ms)
  ) {
    throw new Error("baseline report has no workspace switch metrics");
  }
  return {
    metrics: {
      aToB: { p50Ms: aToB.p50Ms, p95Ms: aToB.p95Ms },
      bToA: { p50Ms: bToA.p50Ms, p95Ms: bToA.p95Ms },
    },
  };
}

/**
 * 接收一个完整 Responses 请求并限制 body 大小；测试只把最后一个 marker 分类，避免
 * Provider fixture 将历史正文或绝对路径写入报告。
 */
async function readRequestBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("loopback request exceeds 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/**
 * 启动仅绑定 127.0.0.1 的确定性 Responses Provider；所有成功回复复用真实 Java adapter
 * 能消费的 SSE 结构，外部网络请求永远不会进入本轮测试。
 */
export async function startSwitchFixture() {
  const attempts = [];
  const server = createServer(async (request, response) => {
    try {
      assert.equal(request.method, "POST");
      assert.equal(request.url, "/v1/responses");
      const payload = await readRequestBody(request);
      const input = typeof payload.input === "string" ? payload.input : JSON.stringify(payload.input);
      const matches = [...input.matchAll(/JA_SWITCH_HISTORY_([AB])_TURN_(\d+)/gu)];
      const latest = matches.at(-1);
      const project = latest?.[1] === "B" ? "b" : latest?.[1] === "A" ? "a" : "unknown";
      const turn = latest === undefined ? 0 : Number(latest[2]);
      const marker = latest === undefined ? "JA_SWITCH_HISTORY_UNKNOWN" : latest[0];
      attempts.push({ project, turn, status: 200 });
      response.writeHead(200, {
        "content-type": "text/event-stream",
        "cache-control": "no-store",
      });
      response.end(responseStream(marker, `switch_${attempts.length}`));
    } catch {
      attempts.push({ project: "unknown", turn: 0, status: 400 });
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "loopback fixture rejected request" }));
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (typeof address !== "object" || address === null) {
    server.close();
    throw new Error("loopback fixture failed to bind");
  }
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    /** 只关闭本 fixture listener 和其连接，不影响其它进程。 */
    async close() {
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

/**
 * 通过真实 Git porcelain 统计压力目录；这一步验证 fixture 的 ignored/untracked 分类，
 * 防止只把配置参数写进报告却没有实际文件规模。
 */
export async function countPressureFiles(workspaceRoot) {
  const run = async (args) =>
    execFileAsync("git.exe", args, {
      cwd: workspaceRoot,
      windowsHide: true,
      timeout: 30_000,
      maxBuffer: 8 * 1024 * 1024,
    });
  const [ignored, untracked] = await Promise.all([
    run(["ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--", ".review-ignored"]),
    run(["ls-files", "--others", "--exclude-standard", "-z", "--", "zz-review-untracked"]),
  ]);
  const count = (output) => output.split("\0").filter((entry) => entry.length > 0).length;
  return { ignoredFiles: count(ignored.stdout), untrackedFiles: count(untracked.stdout) };
}

/**
 * 在 runProduction 已创建的隔离根旁建立第二个真实 Git workspace；只使用本轮 temp root，
 * 让两个项目都拥有相同压力规模而不触碰用户目录。
 */
export async function createSecondaryWorkspace(workspaceRoot) {
  const secondaryRoot = join(dirname(workspaceRoot), "workspace-b");
  const startedAt = performance.now();
  const fixture = await createReviewGitFixture(secondaryRoot, {
    fixture: "full",
    ignoredFiles: REQUIRED_IGNORED_FILES,
    untrackedFiles: REQUIRED_UNTRACKED_FILES,
  });
  const pressure = await countPressureFiles(secondaryRoot);
  assert.equal(pressure.ignoredFiles, REQUIRED_IGNORED_FILES);
  assert.equal(pressure.untrackedFiles, REQUIRED_UNTRACKED_FILES);
  return {
    root: secondaryRoot,
    setupMs: performance.now() - startedAt,
    fixture,
    pressure,
  };
}

/**
 * 两个 server-owned 项目分别登记并显式授信后才创建 Thread；直接调用只准备 fixture，
 * 真正切换仍必须点击侧栏项目按钮并经过 workspace controller。
 */
export async function createProjectsAndThreads(page, workspaceRoot, secondaryRoot) {
  return page.evaluate(
    async ({ firstRoot, secondRoot, labels }) => {
      const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const history = new TauriHistoryAdapter();
      /** 两个目录分别授权，防止后一项目意外借用前一项目的信任状态。 */
      const create = async (cwd, displayName, title) => {
        const workspace = await history.workspaceOpen({ cwd, displayName });
        await history.workspaceSetTrust({ workspaceId: workspace.workspaceId, trust: "trusted" });
        const thread = await history.threadCreate({
          cwd: workspace.root,
          title,
          providerId: "provider_e2e",
          modelId: "model_e2e",
          reasoningLevel: null,
          accessMode: "full_access",
          collaborationMode: "default",
        });
        return {
          workspaceId: workspace.workspaceId,
          threadId: thread.threadId,
          displayName: workspace.displayName,
        };
      };
      return {
        a: await create(firstRoot, labels.a, "切换验收 A 历史会话"),
        b: await create(secondRoot, labels.b, "切换验收 B 历史会话"),
      };
    },
    {
      firstRoot: workspaceRoot,
      secondRoot: secondaryRoot,
      labels: PROJECT_LABELS,
    },
  );
}

/**
 * 健康连接文案可隐藏；以真实 Ja Shell、typed Host 身份和最近对话列表排除半初始化窗口。
 */
async function waitForApplication(page, deadline = Date.now() + 60_000) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ state: "visible", timeout: timeout() });
  try {
    await page.waitForFunction(
      async () => {
        try {
          const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
          const state = await new TauriRuntimeHostAdapter().state();
          return (state.status === "ready" || state.status === "busy") &&
            typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0;
        } catch {
          return false;
        }
      },
      undefined,
      { timeout: timeout() },
    );
  } catch (error) {
    const diagnostic = await page.evaluate(async () => {
      const statuses = [...globalThis.document.querySelectorAll('[role="status"]')].map((node) => ({
        name: node.getAttribute("aria-label"),
        text: node.textContent?.trim() ?? "",
      }));
      let nativeState;
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        nativeState = await new TauriRuntimeHostAdapter().state();
      } catch (failure) {
        nativeState = { error: String(failure?.message ?? failure).slice(0, 500) };
      }
      return { statuses, nativeState };
    });
    throw new Error(`runtime did not reach ready: ${JSON.stringify(diagnostic)}`, { cause: error });
  }
  await ensureRecentHistoryOpen(page, timeout());
}

/**
 * 最近对话分区可能被隔离 profile 的 UI 偏好收起；只通过真实标题栏控件恢复，不直接写 DOM
 * 或 localStorage，保证后续 spinner/row 断言仍经过生产导航组件。
 */
async function ensureRecentHistoryOpen(page, timeoutMs = 30_000) {
  const list = page.getByRole("list", { name: "最近对话列表", exact: true });
  if ((await list.count()) === 0) {
    await page.getByRole("button", { name: "展开最近对话", exact: true }).click({ timeout: timeoutMs });
  }
  await list.waitFor({ state: "visible", timeout: timeoutMs });
}

/** 确保项目分区真实展开；隔离 profile 的 UI 偏好不能让项目按钮从验收 DOM 中消失。 */
async function ensureProjectCatalogOpen(page, timeoutMs = 30_000) {
  const list = page.getByRole("list", { name: "项目列表", exact: true });
  if ((await list.count()) === 0) {
    await page.getByRole("button", { name: "展开项目", exact: true }).click({ timeout: timeoutMs });
  }
  await list.waitFor({ state: "visible", timeout: timeoutMs });
}

/** 等待两个真实项目行同时进入可见目录，并在失败时保留脱敏 DOM/服务端计数诊断。 */
async function waitForProjectCatalog(page, timeoutMs = 60_000) {
  await ensureProjectCatalogOpen(page, timeoutMs);
  try {
    await page.waitForFunction(
      () =>
        globalThis.document.querySelectorAll(
          '[aria-label="项目列表"] button[data-scope-kind="project"]',
        ).length === 2,
      undefined,
      { timeout: timeoutMs },
    );
  } catch (error) {
    const diagnostic = await page.evaluate(async () => {
      const rows = [...globalThis.document.querySelectorAll(
        '[aria-label="项目列表"] button[data-scope-kind="project"]',
      )].map((node) => ({
        text: node.textContent?.trim() ?? "",
        current: node.getAttribute("aria-current"),
        disabled: node.hasAttribute("disabled"),
      }));
      let nativeCount;
      try {
        const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
        const listed = await new TauriHistoryAdapter().workspaceList({ limit: 200 });
        nativeCount = listed.items.length;
      } catch {
        nativeCount = "unavailable";
      }
      return { rows, nativeCount };
    });
    throw new Error(`project catalog did not expose two rows: ${JSON.stringify(diagnostic)}`, {
      cause: error,
    });
  }
}

/**
 * 按显示名称读取项目按钮；aria-label 会随 selected 状态变化，文本 label 则保持稳定，
 * 因此不把状态文案当作项目 identity。
 */
function projectButton(page, displayName) {
  return page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: displayName });
}

/**
 * 通过项目按钮完成一次真实 scope 选择，并等待 server-owned workspace identity 进入 aria-current。
 */
async function selectProject(page, displayName, deadline = Date.now() + 60_000) {
  await ensureProjectCatalogOpen(page, Math.max(1, deadline - Date.now()));
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  const button = projectButton(page, displayName);
  await button.first().waitFor({ state: "visible", timeout: timeout() });
  if ((await button.first().getAttribute("aria-current")) !== "page") {
    await button.first().click({ timeout: timeout() });
  }
  try {
    await page.waitForFunction(
      (expected) =>
        [...globalThis.document.querySelectorAll(
          '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
        )].some((candidate) => candidate.textContent?.trim() === expected),
      displayName,
      { timeout: timeout() },
    );
  } catch (error) {
    const current = await page.locator(
      '[aria-label="项目列表"] button[data-scope-kind="project"]',
    ).evaluateAll((nodes) => nodes.map((node) => ({
      text: node.textContent?.trim() ?? "",
      current: node.getAttribute("aria-current"),
    })));
    throw new Error(`project selection did not settle: ${JSON.stringify({ displayName, current })}`, {
      cause: error,
    });
  }
  await ensureRecentHistoryOpen(page, timeout());
}

/**
 * 等待 Thread 行选中并让真实 Composer 进入可提交状态；新建空 Thread 可能暂时只有空态，
 * 因此 Timeline identity 默认留给已有历史的路径断言；seed 空 Thread 可显式关闭该等待，
 * 产生首条持久事件后再断言，避免把合法空态误报为加载失败。
 */
async function selectThread(
  page,
  threadId,
  strictIdentity,
  deadline = Date.now() + 60_000,
  expectHistory = true,
) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout() });
  if ((await row.getAttribute("aria-current")) !== "page") await row.click({ timeout: timeout() });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout() },
  );
  await waitForComposerReady(page, deadline);
  if (expectHistory) await waitForTimelineIdentity(page, threadId, strictIdentity, deadline);
}

/**
 * 等待发送表单与当前 Thread 绑定；这是空 Thread 能够接受第一条真实历史事件的最小条件，
 * 不依赖 ChatTimeline 是否已经从空态切换为事件列表。
 */
async function waitForComposerReady(page, deadline) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.waitForFunction(
    () => {
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      return input instanceof globalThis.HTMLTextAreaElement && !input.disabled && form?.getAttribute("data-state") === "ready";
    },
    undefined,
    { timeout: timeout() },
  );
}

/**
 * ChatTimeline 必须拥有首条持久历史；after 额外要求 data-thread-id 与侧栏选择一致，防止
 * 旧项目正文短暂留在新项目壳层中。调用方只应在 Composer 已提交首条事件后使用该门禁。
 */
async function waitForTimelineIdentity(page, threadId, strictIdentity, deadline) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  try {
    await page.waitForFunction(
      ({ expected, strict }) => {
        const timeline = globalThis.document.querySelector(".ja-chat-timeline");
        if (timeline === null || timeline.querySelector(".ja-chat-timeline__empty") !== null)
          return false;
        const actual = timeline.getAttribute("data-thread-id");
        return strict ? actual === expected : actual === null || actual === expected;
      },
      { expected: threadId, strict: strictIdentity },
      { timeout: timeout() },
    );
  } catch (error) {
    const state = await timelineState(page);
    throw new Error(`Timeline identity did not settle: ${JSON.stringify({ expected: threadId, state })}`, {
      cause: error,
    });
  }
}

/**
 * 提交一个真实 UI Turn 以产生持久历史正文；padding 让 Timeline 成为可滚动长对话，
 * 但 Provider 只返回 marker，报告不会携带大段用户正文。
 */
async function sendHistoryTurn(page, project, turn, deadline) {
  const marker = `JA_SWITCH_HISTORY_${project.toUpperCase()}_TURN_${turn}`;
  const prompt = `${marker} 请保留这段历史上下文。\n${"长历史上下文用于切换恢复验收。".repeat(320)}`;
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.waitForFunction(
    () => {
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      return input instanceof globalThis.HTMLTextAreaElement && !input.disabled && form?.getAttribute("data-state") === "ready";
    },
    undefined,
    { timeout: timeout() },
  );
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({ timeout: timeout() });
  await page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: marker })
    .waitFor({ state: "visible", timeout: timeout() });
  return marker;
}

/**
 * 为指定项目建立多轮真实历史，并检查最后 marker 可见；首次 seed 经过生产 Composer、
 * turn/start、Java Provider adapter、SQLite event 和 Timeline reducer 全链路。
 */
async function seedProjectHistory(page, project, threadId, strictIdentity, evidenceDirectory, deadline) {
  await selectThread(page, threadId, strictIdentity, deadline, false);
  const markers = [];
  for (let turn = 1; turn <= HISTORY_TURNS; turn += 1)
    markers.push(await sendHistoryTurn(page, project, turn, deadline));
  await waitForTimelineIdentity(page, threadId, strictIdentity, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, `workspace-switch-${project}-seeded.png`),
    animations: "disabled",
  });
  return markers;
}

/**
 * 读取当前 Timeline 的安全几何摘要，不回传正文；rowCount、thread identity 和 scroll range
 * 足以证明目标历史非空且可进入中部滚动。
 */
async function timelineState(page) {
  return page.evaluate(() => {
    const timeline = globalThis.document.querySelector(".ja-chat-timeline");
    const viewport = timeline?.querySelector(".ja-chat-timeline__scroll");
    return {
      explicitThreadId: timeline?.getAttribute("data-thread-id") ?? null,
      rowCount: timeline?.querySelectorAll(".ja-chat-timeline__row").length ?? 0,
      scrollTop: viewport?.scrollTop ?? 0,
      scrollHeight: viewport?.scrollHeight ?? 0,
      clientHeight: viewport?.clientHeight ?? 0,
    };
  });
}

/**
 * 将当前长 Timeline 滚到中部并等待两帧布局稳定；返回的 ratio 是跨项目 A→B→A 的
 * scroll restoration 参照，不把绝对像素写进固定 fixture。
 */
async function scrollTimelineToMiddle(page, deadline) {
  const state = await page.evaluate(async () => {
    const viewport = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    if (!(viewport instanceof globalThis.HTMLElement)) throw new Error("Timeline viewport missing");
    const maxScrollTop = viewport.scrollHeight - viewport.clientHeight;
    if (maxScrollTop < 120) throw new Error("historical Timeline is not scrollable");
    const target = Math.round(maxScrollTop * 0.42);
    viewport.scrollTop = target;
    await new Promise((resolvePromise) =>
      globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolvePromise)),
    );
    return {
      target,
      maxScrollTop: viewport.scrollHeight - viewport.clientHeight,
      actual: viewport.scrollTop,
      ratio: viewport.scrollTop / Math.max(1, viewport.scrollHeight - viewport.clientHeight),
    };
  });
  assert.ok(state.ratio > 0.2 && state.ratio < 0.7);
  void deadline;
  return state;
}

/**
 * @author kongweiguang
 * 在浏览器自身时钟内测量项目切换，同时按 aria-busy 与真实历史行数分支观察标题 spinner；
 * 固定大小计数避免 DOM 高频变化变成无界轨迹，也不让 CDP 往返污染性能样本。
 */
async function measureProjectSwitch(page, displayName, threadId, marker, mode, strictIdentity) {
  return page.evaluate(
    ({ displayName: expectedName, expectedThreadId, expectedMarker, visibility, strict }) =>
      new Promise((resolvePromise, rejectPromise) => {
        const startedAt = globalThis.performance.now();
        const historyLoadingObservations = {
          busyEmpty: 0,
          busyWithRows: 0,
          idleEmpty: 0,
          idleWithRows: 0,
          unavailable: 0,
        };
        const historyLoadingViolations = {
          unavailableHistory: 0,
          invalidBusyValue: 0,
          missingExpectedSpinner: 0,
          invisibleExpectedSpinner: 0,
          misplacedExpectedSpinner: 0,
          multipleSpinners: 0,
          unexpectedSpinnerWhileCachedBusy: 0,
          unexpectedSpinnerWhileIdle: 0,
        };
        let legacyPlaceholderSeen = false;
        let blankFrameCount = 0;
        let paintFrameCount = 0;
        let paintRaf = 0;
        let settled = false;
        let settleScheduled = false;
        let lastState = {};
        const timeout = globalThis.setTimeout(() => {
          settled = true;
          globalThis.cancelAnimationFrame(paintRaf);
          observer.disconnect();
          rejectPromise(new Error(`workspace switch timed out: ${JSON.stringify(lastState)}`));
        }, 20_000);
        const visible = (node) => {
          if (!(node instanceof globalThis.HTMLElement)) return false;
          const style = globalThis.getComputedStyle(node);
          const box = node.getBoundingClientRect();
          return style.visibility !== "hidden" && style.display !== "none" && box.width > 0 && box.height > 0;
        };
        const project = () =>
          [...globalThis.document.querySelectorAll(
            '[aria-label="项目列表"] button[data-scope-kind="project"]',
          )].find((candidate) => candidate.textContent?.trim() === expectedName);
        /** @author kongweiguang 忙空态才要求标题 spinner，缓存忙碌/空闲态必须没有该 indicator。 */
        const captureLoading = () => {
          const history = globalThis.document.querySelector(".ja-navigation-history");
          const heading = history?.querySelector(".ja-navigation-section-heading");
          const list = history?.querySelector(".ja-navigation-history-list");
          if (
            !(history instanceof globalThis.HTMLElement) ||
            !(heading instanceof globalThis.HTMLElement) ||
            !(list instanceof globalThis.HTMLElement)
          ) {
            historyLoadingObservations.unavailable += 1;
            historyLoadingViolations.unavailableHistory += 1;
          } else {
            const busyValue = list.getAttribute("aria-busy");
            const rowCount = list.querySelectorAll("button[data-thread-id]").length;
            const busy = busyValue === "true";
            if (busyValue !== null && busyValue !== "true" && busyValue !== "false") {
              historyLoadingObservations.unavailable += 1;
              historyLoadingViolations.invalidBusyValue += 1;
            } else {
              const branch = busy
                ? rowCount === 0
                  ? "busyEmpty"
                  : "busyWithRows"
                : rowCount === 0
                  ? "idleEmpty"
                  : "idleWithRows";
              historyLoadingObservations[branch] += 1;
              const spinners = history.querySelectorAll(".ja-navigation-history-loading");
              const spinner = spinners.item(0);
              const shouldShowSpinner = busy && rowCount === 0;
              if (shouldShowSpinner) {
                if (!(spinner instanceof globalThis.HTMLElement)) {
                  historyLoadingViolations.missingExpectedSpinner += 1;
                } else {
                  if (!visible(spinner)) historyLoadingViolations.invisibleExpectedSpinner += 1;
                  if (
                    spinner.parentElement !== heading ||
                    spinner.closest(".ja-navigation-history-list") !== null
                  ) {
                    historyLoadingViolations.misplacedExpectedSpinner += 1;
                  }
                }
                if (spinners.length > 1) historyLoadingViolations.multipleSpinners += 1;
              } else if (spinners.length > 0) {
                if (busy) historyLoadingViolations.unexpectedSpinnerWhileCachedBusy += 1;
                else historyLoadingViolations.unexpectedSpinnerWhileIdle += 1;
              }
            }
          }
          if (/正在读取(?:会话|历史)/u.test(list?.textContent ?? "")) legacyPlaceholderSeen = true;
        };
        // 用浏览器 paint 时钟观察切换中实际可见的 Timeline；DOM mutation 本身可能在一帧内
        // 经历空态，只有被绘制出来才算用户可见的 blank frame。
        const sampleTimelinePaint = () => {
          if (settled) return;
          paintFrameCount += 1;
          const timeline = globalThis.document.querySelector(".ja-chat-timeline");
          if (!(timeline instanceof globalThis.HTMLElement) || !visible(timeline)) {
            blankFrameCount += 1;
          } else {
            const rowCount = timeline.querySelectorAll(".ja-chat-timeline__row").length;
            if (rowCount === 0 || timeline.querySelector(".ja-chat-timeline__empty") !== null)
              blankFrameCount += 1;
          }
          paintRaf = globalThis.requestAnimationFrame(sampleTimelinePaint);
        };
        const finishAfterPaint = () => {
          if (settled || settleScheduled) return;
          settleScheduled = true;
          globalThis.requestAnimationFrame(() => {
            globalThis.requestAnimationFrame(() => {
              settleScheduled = false;
              finished(true);
            });
          });
        };
        const finished = (afterPaint = false) => {
          captureLoading();
          const selectedProject = project();
          const row = globalThis.document.querySelector(
            `[aria-label="最近对话列表"] button[data-thread-id="${expectedThreadId}"]`,
          );
          const timeline = globalThis.document.querySelector(".ja-chat-timeline");
          const identity = timeline?.getAttribute("data-thread-id");
          const final = [...globalThis.document.querySelectorAll(".ja-chat-message-final")].find(
            (candidate) => candidate.textContent?.includes(expectedMarker),
          );
          const ready =
            selectedProject?.getAttribute("aria-current") === "page" &&
            visible(row) &&
            timeline !== null &&
            timeline.querySelector(".ja-chat-timeline__empty") === null &&
            (strict ? identity === expectedThreadId : identity === null || identity === expectedThreadId) &&
            (visibility === "visible" ? visible(final) : final !== undefined);
          lastState = {
            selectedProject: selectedProject?.getAttribute("aria-current") === "page",
            rowVisible: visible(row),
            timelineIdentity: identity ?? null,
            markerAttached: final !== undefined,
          };
          if (!ready) return;
          if (!afterPaint) {
            finishAfterPaint();
            return;
          }
          settled = true;
          globalThis.clearTimeout(timeout);
          globalThis.cancelAnimationFrame(paintRaf);
          observer.disconnect();
          resolvePromise({
            durationMs: globalThis.performance.now() - startedAt,
            timelineIdentity: identity === expectedThreadId,
            timelineNonEmpty: timeline.querySelectorAll(".ja-chat-timeline__row").length > 0,
            historyLoading: {
              observations: historyLoadingObservations,
              violations: historyLoadingViolations,
            },
            legacyPlaceholderSeen,
            blankFrameCount,
            paintFrameCount,
          });
        };
        const observer = new globalThis.MutationObserver(() => finished());
        observer.observe(globalThis.document.body, {
          subtree: true,
          childList: true,
          attributes: true,
          characterData: true,
        });
        captureLoading();
        const target = project();
        if (!(target instanceof globalThis.HTMLButtonElement)) {
          globalThis.clearTimeout(timeout);
          observer.disconnect();
          rejectPromise(new Error(`project button missing: ${expectedName}`));
          return;
        }
        target.click();
        sampleTimelinePaint();
        finished();
      }),
    {
      displayName,
      expectedThreadId: threadId,
      expectedMarker: marker,
      visibility: mode,
      strict: strictIdentity,
    },
  );
}

/**
 * 读取切换完成后的 scroll ratio；after 要求 A 的中部位置在 B 往返后保持，baseline 仅记录
 * 是否自然恢复，避免改前报告因为目标缺陷丢失其它性能样本。
 */
async function readScrollRestoration(page, expected, strict) {
  const deadline = Date.now() + 3_000;
  while (Date.now() < deadline) {
    const state = await timelineState(page);
    const ratio = state.scrollTop / Math.max(1, state.scrollHeight - state.clientHeight);
    const restored =
      state.rowCount > 0 &&
      state.scrollHeight > state.clientHeight &&
      Math.abs(ratio - expected.ratio) <= 0.12;
    if (restored) return { restored: true, ...state, ratio };
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  const state = await timelineState(page);
  const ratio = state.scrollTop / Math.max(1, state.scrollHeight - state.clientHeight);
  if (strict) throw new Error(`Timeline scroll position was not restored: ${JSON.stringify({ expected: expected.ratio, ratio })}`);
  return { restored: false, ...state, ratio };
}

/**
 * 读取 Review invoke probe 的窄计数；Review 未显示时任何 snapshot 增量都代表隐藏能力误触发。
 */
async function reviewInvokeCounts(page) {
  return page.evaluate(() => {
    const source = globalThis.__JA_REVIEW_REDESIGN_COUNTS__ ?? {};
    return Object.fromEntries(
      Object.entries(source).map(([command, phases]) => [command, { start: Number(phases?.start ?? 0), resolved: Number(phases?.resolved ?? 0), rejected: Number(phases?.rejected ?? 0) }]),
    );
  });
}

/**
 * 组合双向 warm switch 样本，并额外执行一次长历史 A→B→A；切换触发和目标可见均来自真实
 * project/history controls，样本不包含初次 fixture 建立或 page reload 冷启动。
 */
async function measureSwitches(page, projects, markers, strictIdentity, samples, evidenceDirectory) {
  const deadline = Date.now() + 10 * 60_000;
  // reload 后先把两个已有历史项目各访问一次；这段冷进入只用于建立可复用缓存，不进入
  // click->目标正文的热切样本，避免把首次读取的合法 loading 误判为切换回归。
  await selectProject(page, PROJECT_LABELS.b, deadline);
  await selectThread(page, projects.b.threadId, strictIdentity, deadline);
  await selectProject(page, PROJECT_LABELS.a, deadline);
  await selectThread(page, projects.a.threadId, strictIdentity, deadline);
  const scrollBefore = await scrollTimelineToMiddle(page, deadline);
  const aToB = [];
  const bToA = [];
  const restoration = [];
  for (let index = 0; index < samples; index += 1) {
    const switchedToB = await measureProjectSwitch(
      page,
      PROJECT_LABELS.b,
      projects.b.threadId,
      markers.b.at(-1),
      "visible",
      strictIdentity,
    );
    aToB.push(switchedToB);
    const switchedToA = await measureProjectSwitch(
      page,
      PROJECT_LABELS.a,
      projects.a.threadId,
      markers.a.at(-1),
      "attached",
      strictIdentity,
    );
    bToA.push(switchedToA);
    const restored = await readScrollRestoration(page, scrollBefore, strictIdentity);
    restoration.push(restored);
  }
  await page.screenshot({
    path: join(evidenceDirectory, "workspace-switch-a-restored.png"),
    animations: "disabled",
  });
  return {
    aToB,
    bToA,
    scrollBefore,
    restoration,
  };
}

/**
 * @author kongweiguang
 * 执行真实窗口场景并返回完整只读证据；Native runtime identity 只取自共享启动层已验证的产物，
 * 不在 driver 中猜测或重复散列，最终边界一致性由 runProduction 接管报告前的门禁闭合。
 */
export async function runWorkspaceSwitchWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  samples = DEFAULT_SAMPLES,
  phase = "after",
  baseline,
  maven,
  nativeImageIdentity,
}) {
  const strictIdentity = phase === "after";
  await mkdir(evidenceDirectory, { recursive: true });
  const pageErrors = [];
  const threadSeenDiagnostics = {
    probeInstalled: true,
    invocations: [],
    warningAppearanceCount: 0,
    warningEverSeen: false,
    warningVisibleAtEnd: false,
  };
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") pageErrors.push(message.text().slice(0, 500));
  });
  await page.context().addInitScript(createWorkspaceSwitchInvokeProbeBootstrap());
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  const secondary = await createSecondaryWorkspace(workspaceRoot);
  const projects = await createProjectsAndThreads(page, workspaceRoot, secondary.root);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  await waitForProjectCatalog(page, 60_000);
  await selectProject(page, PROJECT_LABELS.a);
  const markersA = await seedProjectHistory(
    page,
    "a",
    projects.a.threadId,
    strictIdentity,
    evidenceDirectory,
    Date.now() + 10 * 60_000,
  );
  await selectProject(page, PROJECT_LABELS.b);
  const markersB = await seedProjectHistory(
    page,
    "b",
    projects.b.threadId,
    strictIdentity,
    evidenceDirectory,
    Date.now() + 10 * 60_000,
  );
  await waitForThreadSeenSettled(page);
  await collectThreadSeenDiagnostics(page, threadSeenDiagnostics);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  await waitForProjectCatalog(page, 60_000);
  const hiddenBefore = await reviewInvokeCounts(page);
  const switches = await measureSwitches(
    page,
    projects,
    { a: markersA, b: markersB },
    strictIdentity,
    samples,
    evidenceDirectory,
  );
  const hiddenAfter = await reviewInvokeCounts(page);
  await waitForThreadSeenSettled(page);
  await collectThreadSeenDiagnostics(page, threadSeenDiagnostics);
  const hiddenReviewSnapshotDelta =
    Number(hiddenAfter.ja_review_snapshot?.start ?? 0) - Number(hiddenBefore.ja_review_snapshot?.start ?? 0);
  const switchRecords = [...switches.aToB, ...switches.bToA];
  const metrics = {
    aToB: summarize(switches.aToB.map((entry) => entry.durationMs)),
    bToA: summarize(switches.bToA.map((entry) => entry.durationMs)),
  };
  const historyLoading = {
    observations: Object.fromEntries(HISTORY_LOADING_OBSERVATION_KEYS.map((key) => [key, 0])),
    violations: Object.fromEntries(HISTORY_LOADING_VIOLATION_KEYS.map((key) => [key, 0])),
  };
  for (const entry of switchRecords) {
    for (const key of HISTORY_LOADING_OBSERVATION_KEYS) {
      historyLoading.observations[key] += entry.historyLoading.observations[key];
    }
    for (const key of HISTORY_LOADING_VIOLATION_KEYS) {
      historyLoading.violations[key] += entry.historyLoading.violations[key];
    }
  }
  const spinnerLegacyPlaceholder = switchRecords.some((entry) => entry.legacyPlaceholderSeen);
  const explicitThreadIdentity = switchRecords.every((entry) => entry.timelineIdentity);
  const blankFrameCount = switchRecords.reduce((total, entry) => total + entry.blankFrameCount, 0);
  const paintFrameCount = switchRecords.reduce((total, entry) => total + entry.paintFrameCount, 0);
  const noBlankTimeline = switchRecords.every(
    (entry) => entry.timelineNonEmpty && entry.blankFrameCount === 0 && entry.paintFrameCount > 0,
  );
  const scrollRestored = switches.restoration.every((entry) => entry.restored);
  const baselineMetrics = baseline?.metrics;
  const baselineEvidence =
    baselineMetrics === undefined
      ? {
          available: false,
          absoluteOnly: true,
          reason:
            phase === "baseline"
              ? "baseline 阶段未提供更早报告，当前值作为修改前绝对基线"
              : "未提供独立修改前报告，当前 checkout 并行修改期间只记录绝对值",
        }
      : {
          available: true,
          absoluteOnly: false,
          source: "provided_report",
          comparison: {
            aToBP50DeltaMs: metrics.aToB.p50Ms - baselineMetrics.aToB.p50Ms,
            aToBP95DeltaMs: metrics.aToB.p95Ms - baselineMetrics.aToB.p95Ms,
            bToAP50DeltaMs: metrics.bToA.p50Ms - baselineMetrics.bToA.p50Ms,
            bToAP95DeltaMs: metrics.bToA.p95Ms - baselineMetrics.bToA.p95Ms,
          },
        };
  const report = {
    schemaVersion: 1,
    phase,
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: nativeImageIdentity === undefined ? "jvm_jar" : "native_sidecar",
      nativeImageVerified: nativeImageIdentity !== undefined,
      nativeImage: nativeImageIdentity,
      toolchain:
        nativeImageIdentity === undefined
          ? { jdkMajor: 25, mavenJavaMajor: maven.javaMajor, nodeMajor: 24, pnpm: "10.33.0" }
          : { nodeMajor: 24, pnpm: "10.33.0" },
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      attempts: pageErrors.length === 0 ? undefined : undefined,
    },
    fixture: {
      projectCount: 2,
      ignoredFiles: REQUIRED_IGNORED_FILES,
      untrackedFiles: REQUIRED_UNTRACKED_FILES,
      setupMs: secondary.setupMs,
      projects: [
        { key: "a", historicalText: markersA.length === HISTORY_TURNS, pressure: await countPressureFiles(workspaceRoot) },
        { key: "b", historicalText: markersB.length === HISTORY_TURNS, pressure: secondary.pressure },
      ],
    },
    metrics,
    correctness: {
      projectsScoped: projects.a.workspaceId !== projects.b.workspaceId,
      noBlankTimeline,
      blankFrameCount,
      paintFrameCount,
      explicitThreadIdentity,
      scrollRestored,
      spinnerLegacyPlaceholder,
      historyLoading,
      hiddenReviewSnapshotDelta,
      pageErrors: pageErrors.length,
      threadSeen: threadSeenDiagnostics,
      persistenceReload: true,
      sampleCount: switchRecords.length,
    },
    baseline: baselineEvidence,
    screenshots: [
      "workspace-switch-a-seeded.png",
      "workspace-switch-b-seeded.png",
      "workspace-switch-a-restored.png",
    ],
  };
  return report;
}

/**
 * 以 JDK25 置前的 PATH 执行 Maven 版本核对；production runner 自身还会再次核对 Java/Node/pnpm，
 * 这里补充任务单要求的 Maven Java 版本证据，避免 JAR 启动和构建使用不同 JDK。
 */
export async function validateMavenToolchain(javaHome) {
  const home = resolve(javaHome);
  const java = join(home, "bin", "java.exe");
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const environment = { ...process.env, JAVA_HOME: home, PATH: [dirname(java), inheritedPath].filter(Boolean).join(";") };
  delete environment.Path;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete environment[name];
  const javaVersion = await execFileAsync(java, ["-version"], {
    env: environment,
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 512 * 1024,
  });
  const javaOutput = `${javaVersion.stdout}\n${javaVersion.stderr}`;
  if (!/version\s+"25(?:[.]|\s)/iu.test(javaOutput)) throw new Error("JAVA_HOME must resolve JDK 25");
  // Windows .cmd 包装器通过宿主 PowerShell 启动，
  // 避免在 cmd 脚本不能直接执行的环境中由 Node 返回 EINVAL。
  const { stdout, stderr } = await execFileAsync("pwsh.exe", ["-NoProfile", "-NonInteractive", "-Command", "& mvn.cmd -version"], {
    env: environment,
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 512 * 1024,
  });
  const output = `${stdout}\n${stderr}`;
  const match = /Java version:\s*([0-9]+)/u.exec(output);
  if (match?.[1] !== "25") throw new Error("Maven must resolve JDK 25");
  return { javaMajor: Number(match[1]) };
}

/** 读取并比对 JAR 内嵌构建版本，避免前端/宿主升级后误启动旧 App Server。 */
export async function validateEmbeddedJarVersion(jarPath, javaHome) {
  const extractionRoot = await mkdtemp(join(tmpdir(), "ja-workspace-switch-jar-"));
  try {
    const jarExecutable = join(resolve(javaHome), "bin", "jar.exe");
    await execFileAsync(
      jarExecutable,
      [
        "xf",
        resolve(jarPath),
        "BOOT-INF/classes/ja-build.properties",
        "BOOT-INF/classes/META-INF/maven/io.github.kongweiguang/ja-app-server/pom.properties",
      ],
      { cwd: extractionRoot, windowsHide: true, timeout: 30_000, maxBuffer: 512 * 1024 },
    );
    const buildProperties = await readFile(
      join(extractionRoot, "BOOT-INF", "classes", "ja-build.properties"),
      "utf8",
    );
    const pomProperties = await readFile(
      join(
        extractionRoot,
        "BOOT-INF",
        "classes",
        "META-INF",
        "maven",
        "io.github.kongweiguang",
        "ja-app-server",
        "pom.properties",
      ),
      "utf8",
    );
    const packageDocument = JSON.parse(await readFile(join(repoRoot, "package.json"), "utf8"));
    const embeddedProductVersion = /^product\.version=([^\r\n]+)$/mu.exec(buildProperties)?.[1];
    const embeddedPomVersion = /^version=([^\r\n]+)$/mu.exec(pomProperties)?.[1];
    const packageVersion = typeof packageDocument.version === "string" ? packageDocument.version : undefined;
    if (
      embeddedProductVersion === undefined ||
      embeddedPomVersion === undefined ||
      packageVersion === undefined ||
      embeddedProductVersion !== embeddedPomVersion ||
      embeddedProductVersion !== packageVersion
    ) {
      throw new Error(
        `JAR version mismatch: jar=${embeddedProductVersion ?? "missing"} pom=${embeddedPomVersion ?? "missing"} package=${packageVersion ?? "missing"}`,
      );
    }
    return { embeddedProductVersion, embeddedPomVersion, packageVersion };
  } finally {
    await rm(extractionRoot, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }).catch(
      () => undefined,
    );
  }
}

/**
 * @author kongweiguang
 * JAR 才读取内嵌版本并校验 Maven；Native 产物使用共享启动层的无 fallback 指纹验证，报告门禁在其执行身份补齐后运行。
 * driver 的局部采样先留在 partialReport，门禁失败时外层只补写已完成证据并沿用失败现场保留边界。
 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const nativeSidecar = options.nativeExecutable !== undefined;
  const embeddedJar = nativeSidecar
    ? undefined
    : await validateEmbeddedJarVersion(options.jar, options.javaHome);
  const maven = nativeSidecar ? undefined : await validateMavenToolchain(options.javaHome);
  if (options.preflightOnly) {
    const toolchain = await validateToolchain(options);
    const edgeDriver = options.edgeDriver === undefined ? undefined : await validateEdgeDriver(options.edgeDriver);
    console.log(`JA_WORKSPACE_SWITCH_PREFLIGHT_OK ${JSON.stringify({ toolchain, maven, embeddedJar, edgeDriver })}`);
    return;
  }
  const baseline = options.baselineReport === undefined ? undefined : await readBaselineReport(options.baselineReport);
  const fixture = await startSwitchFixture();
  let partialReport;
  try {
    /** @author kongweiguang 先捕获原始测量对象；mode/identity 门禁由共享 runner 补齐 execution 后调用。 */
    const runValidatedDriver = async (driverOptions) => {
      const report = await runWorkspaceSwitchWebView2({
        ...driverOptions,
        samples: options.samples,
        phase: options.phase,
        baseline,
        maven: nativeSidecar ? undefined : { ...maven, embeddedJar },
      });
      partialReport = report;
      const expectedAttempts = HISTORY_TURNS * 2;
      const actualAttempts = fixture.attempts.filter((attempt) => attempt.status === 200).length;
      if (actualAttempts < expectedAttempts)
        throw new Error(
          `loopback fixture attempts incomplete: ${actualAttempts}/${expectedAttempts}`,
        );
      return report;
    };
    try {
      await runProduction({
        ...options,
        hiddenWindow: true,
        prewarmWebview: true,
        providerBaseUrl: fixture.baseUrl,
        ignoredFiles: Math.max(options.ignoredFiles, REQUIRED_IGNORED_FILES),
        untrackedFiles: Math.max(options.untrackedFiles, REQUIRED_UNTRACKED_FILES),
        fixture: "full",
        preserveFailedProfile: true,
        reportFileName: "workspace-switch-webview2-report.json",
        driver: runValidatedDriver,
        validateReport: validateWorkspaceSwitchReport,
      });
    } catch (error) {
      if (partialReport !== undefined) {
        await writeFile(
          join(options.evidenceDirectory, "workspace-switch-webview2-report.json"),
          `${JSON.stringify(workspaceSwitchFailureReport(partialReport, error), null, 2)}\n`,
          "utf8",
        );
      }
      throw error;
    }
    console.log(
      `JA_WORKSPACE_SWITCH_WEBVIEW2_${options.phase.toUpperCase()} ${JSON.stringify({ samples: options.samples })}`,
    );
  } finally {
    await fixture.close();
  }
}

if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`JA_WORKSPACE_SWITCH_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`);
    process.exitCode = 1;
  });
}
