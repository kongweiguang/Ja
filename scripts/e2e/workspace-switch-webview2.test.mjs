// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createContext, runInContext } from "node:vm";
import {
  createWorkspaceSwitchInvokeProbeBootstrap,
  installThreadSeenInvokeProbe,
  parseArguments,
  percentile,
  readBaselineReport,
  summarize,
  validateMavenToolchain,
  validateWorkspaceSwitchReport,
  waitForThreadSeenSettled,
  workspaceSwitchFailureReport,
} from "./workspace-switch-webview2.mjs";

/** @author kongweiguang 观察结束只能等待既有调用，不能借测试等待改变快速连续输入或吞掉浏览器错误。 */
test("threadSeen settlement waits without mutation and preserves timeout diagnostics", async () => {
  const context = createContext({
    __JA_E2E_THREAD_SEEN_DIAGNOSTICS__: { invocations: [{ outcome: "pending" }] },
  });
  let calls = 0;
  const page = {
    /** 在隔离 VM 中执行真实 predicate，验证它只看已有命令状态。 */
    async waitForFunction(predicate, argument, options) {
      calls += 1;
      assert.equal(argument, undefined);
      assert.deepEqual(options, { timeout: 123, polling: 100 });
      const check = runInContext(`(${predicate.toString()})`, context);
      assert.equal(check(), false);
      context.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__.invocations[0].outcome = "resolved";
      assert.equal(check(), true);
    },
  };
  assert.equal(await waitForThreadSeenSettled(page, 123), true);
  assert.equal(calls, 1);
  const timeout = Object.assign(new Error("deadline"), { name: "TimeoutError" });
  const timeoutPage = {
    /** 超时不清空 pending，后续 collect/validator 仍保留失败证据。 */
    async waitForFunction() {
      throw timeout;
    },
  };
  assert.equal(await waitForThreadSeenSettled(timeoutPage), false);
  const disconnected = new Error("disconnected");
  const disconnectedPage = {
    /** 非 Deadline 故障必须原样传播，不能伪装成正常观察结束。 */
    async waitForFunction() {
      throw disconnected;
    },
  };
  await assert.rejects(
    waitForThreadSeenSettled(disconnectedPage),
    (error) => error === disconnected,
  );
});

/** @author kongweiguang Native/JAR 报告共享同一测量面，但各自只声明所属运行边界与工具链事实。 */
function validReport(phase = "after", boundary = "jvm_jar") {
  const nativeSidecar = boundary === "native_sidecar";
  const nativeImage = nativeSidecar
    ? {
        sha256: "a".repeat(64),
        sizeBytes: 1_024,
        resourceName: "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
        noFallback: true,
        sourceHashMatched: true,
      }
    : undefined;
  const tauri = {
    pid: 202,
    launcherPid: 101,
    executablePath: "C:\\ja-test-target\\debug\\ja-desktop.exe",
    sha256: "d".repeat(64),
    sizeBytes: 2_048,
  };
  return {
    schemaVersion: 1,
    phase,
    runtime: {
      platform: "win32",
      surface: "tauri_webview2",
      boundary,
      nativeImageVerified: nativeSidecar,
      ...(nativeSidecar ? { nativeImage } : {}),
      toolchain: nativeSidecar
        ? { nodeMajor: 24, pnpm: "10.33.0" }
        : { jdkMajor: 25, mavenJavaMajor: 25, nodeMajor: 24, pnpm: "10.33.0" },
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0 },
    fixture: { projectCount: 2, ignoredFiles: 4_500, untrackedFiles: 2_000 },
    metrics: {
      aToB: { count: 5, p50Ms: 120, p95Ms: 180, samples: [100, 120, 130, 150, 180] },
      bToA: { count: 5, p50Ms: 110, p95Ms: 170, samples: [90, 110, 120, 140, 170] },
    },
    correctness: {
      projectsScoped: true,
      noBlankTimeline: true,
      blankFrameCount: 0,
      paintFrameCount: 100,
      explicitThreadIdentity: true,
      scrollRestored: true,
      spinnerLegacyPlaceholder: false,
      historyLoading: {
        observations: {
          busyEmpty: 2,
          busyWithRows: 4,
          idleEmpty: 1,
          idleWithRows: 8,
          unavailable: 0,
        },
        violations: {
          unavailableHistory: 0,
          invalidBusyValue: 0,
          missingExpectedSpinner: 0,
          invisibleExpectedSpinner: 0,
          misplacedExpectedSpinner: 0,
          multipleSpinners: 0,
          unexpectedSpinnerWhileCachedBusy: 0,
          unexpectedSpinnerWhileIdle: 0,
        },
      },
      hiddenReviewSnapshotDelta: 0,
      pageErrors: 0,
      threadSeen: {
        probeInstalled: true,
        invocations: [
          {
            ordinal: 1,
            expectedThreadRevision: 7,
            outcome: "resolved",
            resultValid: true,
            resultRevision: 8,
            latestTurnStatus: "completed",
            latestTurnSeen: true,
          },
        ],
        warningAppearanceCount: 0,
        warningEverSeen: false,
        warningVisibleAtEnd: false,
      },
    },
    baseline: { available: false, absoluteOnly: true },
    ...(nativeSidecar
      ? {
          execution: {
            boundary: "native_sidecar",
            nativeImageVerified: true,
            nativeImage: { ...nativeImage },
            tauri: { ...tauri },
            jar: undefined,
          },
        }
      : {
          execution: {
            boundary: "debug_jar",
            nativeImageVerified: false,
            nativeImage: undefined,
            tauri: { ...tauri },
            jar: { sha256: "c".repeat(64), size: 123 },
          },
        }),
  };
}

/** @author kongweiguang 在隔离 VM 中模拟 WebView globals，验证注入 probe 不改委托结果且只留脱敏字段。 */
function createThreadSeenProbeHarness(previous, installThreadSeen = true) {
  const observers = [];
  const alerts = [];
  const document = {
    documentElement: {},
    querySelectorAll(selector) {
      assert.equal(selector, ".ja-navigation-error, .ja-notification-content");
      return alerts;
    },
    addEventListener() {},
  };
  class FakeMutationObserver {
    constructor(callback) {
      this.callback = callback;
      observers.push(this);
    }
    observe(_target, options) {
      this.options = options;
    }
  }
  const context = createContext({
    document,
    MutationObserver: FakeMutationObserver,
    getComputedStyle: (element) =>
      element?.computedStyle ?? { display: "block", visibility: "visible", opacity: "1" },
  });
  if (previous !== undefined) context.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous;
  if (installThreadSeen) {
    const install = runInContext(`(${installThreadSeenInvokeProbe.toString()})`, context);
    install(["CONFLICT", "RUNTIME_UNAVAILABLE"]);
  }
  return { context, observers, alerts };
}

/** @author kongweiguang 线程已读探针必须逐字转交既有 wrapper/delegate，错误只留下白名单码或固定 sentinel。 */
test("threadSeen probe preserves chained results and errors without retaining private payloads", async () => {
  const forwarded = [];
  const previous = async (request, delegate) => {
    forwarded.push({ request, delegate });
    return delegate();
  };
  const harness = createThreadSeenProbeHarness(previous);
  const request = {
    command: "ja_thread_seen",
    args: {
      input: {
        threadId: "thr_PRIVATE_IDENTIFIER",
        expectedThreadRevision: 41,
        body: "PRIVATE_BODY",
        path: "C:\\PRIVATE_PATH\\secret.txt",
      },
    },
  };
  const result = {
    threadId: "thr_PRIVATE_IDENTIFIER",
    title: "PRIVATE_TITLE",
    body: "PRIVATE_BODY",
    path: "C:\\PRIVATE_PATH\\secret.txt",
    revision: 42,
    latestTurnStatus: "completed",
    latestTurnSeen: true,
  };
  const delegate = async () => result;
  assert.strictEqual(
    await harness.context.__JA_E2E_NATIVE_INVOKE_PROBE__(request, delegate),
    result,
  );
  assert.strictEqual(forwarded[0].request, request);
  assert.strictEqual(forwarded[0].delegate, delegate);
  const unrelatedRequest = { command: "ja_review_snapshot" };
  assert.strictEqual(
    await harness.context.__JA_E2E_NATIVE_INVOKE_PROBE__(unrelatedRequest, delegate),
    result,
  );
  assert.strictEqual(forwarded[1].request, unrelatedRequest);

  const knownFailure = Object.assign(new Error("PRIVATE_ERROR_TEXT"), { code: "CONFLICT" });
  await assert.rejects(
    harness.context.__JA_E2E_NATIVE_INVOKE_PROBE__(request, async () => {
      throw knownFailure;
    }),
    (error) => error === knownFailure,
  );
  const unknownFailure = Object.assign(new Error("PRIVATE_UNKNOWN_ERROR_TEXT"), {
    code: "PRIVATE_UNKNOWN_CODE",
  });
  await assert.rejects(
    harness.context.__JA_E2E_NATIVE_INVOKE_PROBE__(request, async () => {
      throw unknownFailure;
    }),
    (error) => error === unknownFailure,
  );

  const diagnostics = JSON.parse(
    JSON.stringify(harness.context.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__),
  );
  assert.deepEqual(diagnostics.invocations, [
    {
      ordinal: 1,
      expectedThreadRevision: 41,
      outcome: "resolved",
      resultValid: true,
      resultRevision: 42,
      latestTurnStatus: "completed",
      latestTurnSeen: true,
    },
    { ordinal: 2, expectedThreadRevision: 41, outcome: "rejected", errorCode: "CONFLICT" },
    {
      ordinal: 3,
      expectedThreadRevision: 41,
      outcome: "rejected",
      errorCode: "INVOKE_FAILED",
    },
  ]);
  const serialized = JSON.stringify(diagnostics);
  for (const privateValue of [
    "thr_PRIVATE_IDENTIFIER",
    "PRIVATE_TITLE",
    "PRIVATE_BODY",
    "PRIVATE_PATH",
    "PRIVATE_ERROR_TEXT",
    "PRIVATE_UNKNOWN_ERROR_TEXT",
    "PRIVATE_UNKNOWN_CODE",
  ]) {
    assert.equal(
      serialized.includes(privateValue),
      false,
      `${privateValue} leaked into diagnostics`,
    );
  }
});

/** @author kongweiguang 单一 InitScript 固定 Review → threadSeen 安装顺序，确保两层观察链不会互相覆盖。 */
test("workspace probe bootstrap installs the Review wrapper before threadSeen", async () => {
  const harness = createThreadSeenProbeHarness(undefined, false);
  runInContext(createWorkspaceSwitchInvokeProbeBootstrap(), harness.context);

  const result = { revision: 9, latestTurnStatus: "running", latestTurnSeen: false };
  const delegate = async () => result;
  assert.strictEqual(
    await harness.context.__JA_E2E_NATIVE_INVOKE_PROBE__(
      { command: "ja_thread_seen", args: { input: { expectedThreadRevision: 8 } } },
      delegate,
    ),
    result,
  );
  assert.deepEqual(
    JSON.parse(JSON.stringify(harness.context.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__)).invocations,
    [
      {
        ordinal: 1,
        expectedThreadRevision: 8,
        outcome: "resolved",
        resultValid: true,
        resultRevision: 9,
        latestTurnStatus: "running",
        latestTurnSeen: false,
      },
    ],
  );
  assert.equal(harness.context.__JA_REVIEW_REDESIGN_CALLS__.length, 0);
});

/** @author kongweiguang 警告计数按可见状态从无到有的 episode 递增，不按 toast 与侧栏的重复投影重复计数。 */
test("threadSeen warning observer counts only visible appearance transitions", () => {
  const harness = createThreadSeenProbeHarness();
  const observer = harness.observers[0];
  const makeAlert = (
    visible,
    text = "未读状态暂时无法同步，请重新打开会话重试。",
    computedStyle,
  ) => ({
    isConnected: true,
    parentElement: null,
    textContent: text,
    computedStyle,
    getAttribute: () => "alert",
    getClientRects: () => (visible ? [{}] : []),
  });
  const diagnostics = () => harness.context.__JA_E2E_THREAD_SEEN_DIAGNOSTICS__;
  assert.ok(observer.options.attributeFilter.includes("data-mounted"));

  harness.alerts.push(makeAlert(true), makeAlert(true));
  observer.callback([]);
  observer.callback([]);
  assert.equal(diagnostics().warningAppearanceCount, 1);
  assert.equal(diagnostics().warningEverSeen, true);
  assert.equal(diagnostics().warningVisible, true);

  harness.alerts.splice(0);
  observer.callback([]);
  harness.alerts.push(makeAlert(false));
  observer.callback([]);
  assert.equal(diagnostics().warningAppearanceCount, 1);
  harness.alerts.splice(0);
  harness.alerts.push(
    makeAlert(true, undefined, { display: "block", visibility: "visible", opacity: "0" }),
  );
  observer.callback([]);
  assert.equal(diagnostics().warningAppearanceCount, 1);
  harness.alerts.splice(0);
  const hiddenParent = {
    parentElement: null,
    computedStyle: { display: "block", visibility: "visible", opacity: "0" },
  };
  const childOfHiddenParent = makeAlert(true);
  childOfHiddenParent.parentElement = hiddenParent;
  harness.alerts.push(childOfHiddenParent);
  observer.callback([]);
  assert.equal(diagnostics().warningAppearanceCount, 1);
  harness.alerts.splice(0);
  harness.alerts.push(makeAlert(true));
  observer.callback([]);
  assert.equal(diagnostics().warningAppearanceCount, 2);
});

/** 参数必须保留隔离生产 launch，并固定压力 fixture 下限与可重复样本边界。 */
test("parseArguments 固定双项目压力与阶段参数", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-workspace-switch-evidence"),
    "--jar",
    join(tmpdir(), "ja-app-server.jar"),
    "--untracked-files",
    "2000",
    "--samples",
    "7",
    "--phase",
    "baseline",
  ]);
  assert.equal(parsed.samples, 7);
  assert.equal(parsed.phase, "baseline");
  assert.equal(parsed.ignoredFiles, 4_500);
  assert.equal(parsed.untrackedFiles, 2_000);
  assert.match(parsed.cargoTargetDirectory, /codex-workspace-switch$/u);
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        "C:\\Temp\\evidence",
        "--jar",
        "C:\\Temp\\ja.jar",
        "--samples",
        "2",
      ]),
    /between 3 and 20/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        "C:\\Temp\\evidence",
        "--jar",
        "C:\\Temp\\ja.jar",
        "--phase",
        "unexpected",
      ]),
    /baseline or after/u,
  );
});

/** @author kongweiguang Native launch 经 workspace 参数包装后仍必须携带与产物对应的固定 SHA。 */
test("parseArguments 选择 Native sidecar 且不允许 JAR 回退", () => {
  const executable = join(tmpdir(), "ja-app-server.exe");
  const digest = "a".repeat(64);
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-workspace-switch-native-evidence"),
    "--native-executable",
    executable,
    "--expected-native-sha256",
    digest.toUpperCase(),
    "--ignored-files",
    "4500",
    "--untracked-files",
    "2000",
  ]);
  assert.equal(parsed.nativeExecutable, executable);
  assert.equal(parsed.expectedNativeSha256, digest);
  assert.equal(parsed.jar, undefined);
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        join(tmpdir(), "ja-workspace-switch-native-evidence"),
        "--native-executable",
        executable,
      ]),
    /requires --expected-native-sha256/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        join(tmpdir(), "ja-workspace-switch-native-evidence"),
        "--jar",
        join(tmpdir(), "ja-app-server.jar"),
        "--native-executable",
        executable,
        "--expected-native-sha256",
        digest,
      ]),
    /mutually exclusive/u,
  );
});

/** direct CDP attach 会失去 loopback settings owner，不得被双项目 runner 悄悄接受。 */
test("parseArguments 拒绝 attach 与低于压力下限的 launch", () => {
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        "C:\\Temp\\evidence",
        "--scope",
        "git",
        "--cdp-endpoint",
        "http://127.0.0.1:9222",
        "--workspace-root",
        "C:\\Temp\\workspace",
        "--owned-profile-root",
        "C:\\Temp\\profile",
        "--frontend-port",
        "1427",
      ]),
    /isolated launch/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        "C:\\Temp\\evidence",
        "--jar",
        "C:\\Temp\\ja.jar",
        "--ignored-files",
        "4499",
      ]),
    /at least 4500/u,
  );
});

/** nearest-rank p50/p95 必须对应真实样本，并保留完整 raw 分布。 */
test("percentile 与 summarize 保留真实样本边界", () => {
  assert.equal(percentile([9, 1, 5, 3, 7], 0.5), 5);
  assert.equal(percentile([9, 1, 5, 3, 7], 0.95), 9);
  assert.deepEqual(summarize([9, 1, 5, 3, 7]), {
    count: 5,
    minMs: 1,
    p50Ms: 5,
    p95Ms: 9,
    maxMs: 9,
    samples: [9, 1, 5, 3, 7],
  });
});

/** @author kongweiguang raw driver 尚无共享 launcher 的实进程身份，持久化 PASS 才可接受完整验收回执。 */
test("validateWorkspaceSwitchReport 要求 actual Tauri identity 并接受 PASS 回执", () => {
  const raw = validReport();
  delete raw.execution;
  assert.throws(() => validateWorkspaceSwitchReport(raw), /execution\.tauri\.pid/u);

  const receipt = validReport();
  receipt.verdict = "PASS";
  assert.equal(validateWorkspaceSwitchReport(receipt), receipt);
});

/** @author kongweiguang 身份字段逐项闭合，禁止空 PID、非 Tauri 路径、缺 hash 或无效尺寸冒充运行证据。 */
test("validateWorkspaceSwitchReport 拒绝不完整的实际 Tauri 身份", () => {
  const invalid = [
    ["missing identity", (report) => (report.execution.tauri = undefined)],
    ["missing owner", (report) => (report.execution.tauri.launcherPid = 0)],
    ["same process", (report) => (report.execution.tauri.launcherPid = report.execution.tauri.pid)],
    ["relative path", (report) => (report.execution.tauri.executablePath = "ja-desktop.exe")],
    [
      "wrong app",
      (report) => (report.execution.tauri.executablePath = "C:\\target\\debug\\other.exe"),
    ],
    ["missing hash", (report) => (report.execution.tauri.sha256 = "")],
    ["invalid size", (report) => (report.execution.tauri.sizeBytes = 0)],
  ];
  for (const [name, mutate] of invalid) {
    const report = validReport();
    mutate(report);
    assert.throws(() => validateWorkspaceSwitchReport(report), undefined, name);
  }
});

/** @author kongweiguang Native report 必须和共享启动层回执在边界、摘要、尺寸与 no-fallback 上逐项一致。 */
test("validateWorkspaceSwitchReport 接受匹配的 Native sidecar 身份并拒绝降级或漂移", () => {
  const native = validReport("after", "native_sidecar");
  assert.equal(validateWorkspaceSwitchReport(native), native);

  const invalidReports = [
    ["hash mismatch", (report) => (report.execution.nativeImage.sha256 = "b".repeat(64))],
    [
      "invalid size",
      (report) => (report.runtime.nativeImage.sizeBytes = Number.MAX_SAFE_INTEGER + 1),
    ],
    ["wrong resource", (report) => (report.runtime.nativeImage.resourceName = "server.exe")],
    ["fallback allowed", (report) => (report.runtime.nativeImage.noFallback = false)],
    ["source hash mismatch", (report) => (report.runtime.nativeImage.sourceHashMatched = false)],
    ["JAR fallback", (report) => (report.execution.jar = { sha256: "c".repeat(64), size: 123 })],
    ["JDK claim", (report) => (report.runtime.toolchain.jdkMajor = 25)],
    ["wrong execution boundary", (report) => (report.execution.boundary = "debug_jar")],
  ];
  for (const [name, mutate] of invalidReports) {
    const report = structuredClone(native);
    mutate(report);
    assert.throws(() => validateWorkspaceSwitchReport(report), undefined, name);
  }
});

/** @author kongweiguang JAR 与 Native 回执同样必须携带共享 launcher 捕获的桌面身份。 */
test("validateWorkspaceSwitchReport 同时接受带身份的 JAR execution 回执", () => {
  const report = validReport();
  report.execution = {
    boundary: "debug_jar",
    nativeImageVerified: false,
    nativeImage: undefined,
    tauri: { ...validReport().execution.tauri },
    jar: { sha256: "c".repeat(64), size: 123 },
  };
  assert.equal(validateWorkspaceSwitchReport(report), report);
});

/** @author kongweiguang 静态契约锁定 mode 预检、已验证 Native identity 传递和共享启动后回读顺序，不启动 Runtime。 */
test("Native pressure wiring skips JAR checks and validates the shared runtime identity", async () => {
  const workspaceSource = await readFile(
    new URL("./workspace-switch-webview2.mjs", import.meta.url),
    "utf8",
  );
  const sharedSource = await readFile(
    new URL("./review-redesign-production.mjs", import.meta.url),
    "utf8",
  );
  assert.match(workspaceSource, /const nativeSidecar = options\.nativeExecutable !== undefined/u);
  assert.match(
    workspaceSource,
    /nativeSidecar\s*\?\s*undefined\s*:\s*await validateEmbeddedJarVersion\(options\.jar, options\.javaHome\)/u,
  );
  assert.match(
    workspaceSource,
    /nativeSidecar\s*\?\s*undefined\s*:\s*await validateMavenToolchain\(options\.javaHome\)/u,
  );
  assert.match(workspaceSource, /validateReport:\s*validateWorkspaceSwitchReport/u);

  const driverCall = sharedSource.indexOf("const driverReport = await driver({");
  const processCapture = sharedSource.indexOf(
    "tauriExecutableIdentity = await readOwnedTauriExecutableIdentity(",
  );
  const passedIdentity = sharedSource.indexOf("nativeImageIdentity,", driverCall);
  const executionReceipt = sharedSource.indexOf("driverReport.execution = {", passedIdentity);
  const nativeImageRecheck = sharedSource.indexOf(
    "if (nativeImageIdentity !== undefined)",
    executionReceipt,
  );
  const tauriIdentityRecheck = sharedSource.indexOf(
    "if (tauriExecutableIdentity !== undefined)",
    nativeImageRecheck,
  );
  const reportValidation = sharedSource.indexOf(
    "await validateReport?.(driverReport)",
    tauriIdentityRecheck,
  );
  assert.ok(processCapture > sharedSource.indexOf("await waitForCdp(endpoint, launch)"));
  assert.ok(driverCall > processCapture && passedIdentity > driverCall);
  assert.ok(executionReceipt > passedIdentity && nativeImageRecheck > executionReceipt);
  assert.ok(tauriIdentityRecheck > nativeImageRecheck && reportValidation > tauriIdentityRecheck);
  assert.match(
    sharedSource.slice(executionReceipt, nativeImageRecheck),
    /nativeImage:\s*nativeImageIdentity/u,
  );
  assert.match(
    sharedSource.slice(executionReceipt, tauriIdentityRecheck),
    /tauri:\s*tauriExecutableIdentity/u,
  );
  assert.match(
    sharedSource.slice(nativeImageRecheck, tauriIdentityRecheck),
    /await sha256\(options\.nativeExecutable\)/u,
  );
  assert.match(
    sharedSource.slice(tauriIdentityRecheck, reportValidation),
    /verifyOwnedTauriExecutableIdentity/u,
  );
  assert.match(
    sharedSource,
    /if \(launch !== undefined\) \{\s*const processSnapshot = await readOwnedProcessSnapshot\(launch\.child\.pid\)/u,
  );
  assert.match(sharedSource, /finally \{[\s\S]*?await terminateOwnedLauncher\(launch\)/u);
  assert.match(
    sharedSource,
    /tauriExecutableIdentity === undefined \? \{\} : \{ tauri: tauriExecutableIdentity \}/u,
  );
});

/** @author kongweiguang 已持久化失败或未知 verdict、页面异常、旧占位与隐藏 IO 退化都必须关闭门禁。 */
test("validateWorkspaceSwitchReport 拒绝失败回执、页面异常与 after 退化", () => {
  for (const verdict of ["FAIL", "UNKNOWN", null, undefined]) {
    const invalidReceipt = validReport();
    invalidReceipt.verdict = verdict;
    assert.throws(
      () => validateWorkspaceSwitchReport(invalidReceipt),
      /verdict=/u,
      `verdict=${verdict}`,
    );
  }
  const pageError = validReport();
  pageError.correctness.pageErrors = 1;
  assert.throws(() => validateWorkspaceSwitchReport(pageError), /pageErrors=/u);

  const legacy = validReport();
  legacy.correctness.spinnerLegacyPlaceholder = true;
  assert.throws(() => validateWorkspaceSwitchReport(legacy));
  const missingEmptySpinner = validReport();
  missingEmptySpinner.correctness.historyLoading.violations.missingExpectedSpinner = 1;
  assert.throws(() => validateWorkspaceSwitchReport(missingEmptySpinner));
  for (const violation of [
    "invisibleExpectedSpinner",
    "misplacedExpectedSpinner",
    "multipleSpinners",
    "unexpectedSpinnerWhileCachedBusy",
    "unexpectedSpinnerWhileIdle",
  ]) {
    const invalidLoading = validReport();
    invalidLoading.correctness.historyLoading.violations[violation] = 1;
    assert.throws(() => validateWorkspaceSwitchReport(invalidLoading), violation);
  }
  const hidden = validReport();
  hidden.correctness.hiddenReviewSnapshotDelta = 1;
  assert.throws(() => validateWorkspaceSwitchReport(hidden));
  const blank = validReport();
  blank.correctness.blankFrameCount = 1;
  assert.throws(() => validateWorkspaceSwitchReport(blank));
  const slow = validReport();
  slow.metrics.aToB.p95Ms = 2_001;
  assert.throws(() => validateWorkspaceSwitchReport(slow));
});

/** @author kongweiguang after 必须无可见已读警告；baseline 可记录旧状态，错误与阶段字段仅接受闭集值。 */
test("threadSeen report enforces warning, phase, outcome, status, and error-code contracts", () => {
  const cleanAfter = validReport();
  assert.equal(validateWorkspaceSwitchReport(cleanAfter), cleanAfter);

  const visibleAfter = validReport();
  visibleAfter.correctness.threadSeen.warningAppearanceCount = 1;
  visibleAfter.correctness.threadSeen.warningEverSeen = true;
  assert.throws(() => validateWorkspaceSwitchReport(visibleAfter), /warningAppearanceCount/u);

  const observedBaselineWarning = validReport("baseline");
  observedBaselineWarning.correctness.threadSeen.warningAppearanceCount = 1;
  observedBaselineWarning.correctness.threadSeen.warningEverSeen = true;
  observedBaselineWarning.correctness.threadSeen.warningVisibleAtEnd = true;
  assert.equal(validateWorkspaceSwitchReport(observedBaselineWarning), observedBaselineWarning);

  for (const phase of ["unknown", null, undefined]) {
    const invalidPhase = validReport();
    invalidPhase.phase = phase;
    assert.throws(() => validateWorkspaceSwitchReport(invalidPhase), /phase=/u);
  }
  const invalidOutcome = validReport();
  invalidOutcome.correctness.threadSeen.invocations[0].outcome = "unknown";
  assert.throws(() => validateWorkspaceSwitchReport(invalidOutcome), /outcome=unknown/u);
  const invalidStatus = validReport();
  invalidStatus.correctness.threadSeen.invocations[0].latestTurnStatus = "legacy_status";
  assert.throws(() => validateWorkspaceSwitchReport(invalidStatus), /latestTurnStatus/u);
  const invalidCode = validReport();
  invalidCode.correctness.threadSeen.invocations[0] = {
    ordinal: 1,
    expectedThreadRevision: 7,
    outcome: "rejected",
    errorCode: "PRIVATE_UNKNOWN_CODE",
  };
  assert.throws(() => validateWorkspaceSwitchReport(invalidCode), /errorCode/u);
  const closedUnknownCode = validReport();
  closedUnknownCode.correctness.threadSeen.invocations[0] = {
    ordinal: 1,
    expectedThreadRevision: 7,
    outcome: "rejected",
    errorCode: "INVOKE_FAILED",
  };
  assert.equal(validateWorkspaceSwitchReport(closedUnknownCode), closedUnknownCode);
  const pendingAfter = validReport();
  pendingAfter.correctness.threadSeen.invocations[0] = {
    ordinal: 1,
    expectedThreadRevision: 7,
    outcome: "pending",
  };
  assert.throws(() => validateWorkspaceSwitchReport(pendingAfter), /still pending/u);
});

/** @author kongweiguang 诊断挂在现有 page-local probe 链上，并在 reload 边界汇总安全摘要进入同一报告门禁。 */
test("threadSeen diagnostics chain the existing probe and flow into after acceptance", async () => {
  const source = await readFile(
    new URL("./workspace-switch-webview2.mjs", import.meta.url),
    "utf8",
  );
  assert.match(source, /addInitScript\(createWorkspaceSwitchInvokeProbeBootstrap\(\)\)/u);
  const bootstrap = createWorkspaceSwitchInvokeProbeBootstrap();
  assert.ok(
    bootstrap.indexOf("__JA_REVIEW_REDESIGN_COUNTS__") <
      bootstrap.indexOf("__JA_E2E_THREAD_SEEN_DIAGNOSTICS__"),
  );
  assert.equal(
    (source.match(/collectThreadSeenDiagnostics\(page, threadSeenDiagnostics\)/gu) ?? []).length,
    2,
  );
  assert.match(source, /threadSeen: threadSeenDiagnostics/u);
  assert.match(source, /assert\.equal\(\s*threadSeen\.warningAppearanceCount,\s*0/u);
  assert.match(source, /threadSeen\.warningEverSeen, false/u);
  assert.match(source, /threadSeen\.warningVisibleAtEnd, false/u);
  assert.doesNotMatch(source, /__JA_E2E_THREAD_SEEN_DIAGNOSTICS__[^\n]*threadId/u);
});

/** @author kongweiguang baseline 保留旧 UI 事实；其 loading 违反数只记录，不套用 after 规则。 */
test("validateWorkspaceSwitchReport 允许 baseline 只记录绝对值", () => {
  const report = validReport("baseline");
  report.correctness.spinnerLegacyPlaceholder = true;
  report.correctness.historyLoading.observations.busyEmpty = 0;
  report.correctness.historyLoading.observations.busyWithRows = 0;
  report.correctness.historyLoading.violations.unexpectedSpinnerWhileIdle = 5;
  report.correctness.explicitThreadIdentity = false;
  report.correctness.scrollRestored = false;
  report.baseline = { available: false, absoluteOnly: true };
  assert.equal(validateWorkspaceSwitchReport(report), report);
});

/** @author kongweiguang DOM contract 固定从真实列表 busy/行数采样，并校验 heading spinner 的位置与可见判定。 */
async function assertHistoryLoadingDomContract() {
  const source = await readFile(
    new URL("./workspace-switch-webview2.mjs", import.meta.url),
    "utf8",
  );
  assert.match(source, /querySelector\("\.ja-navigation-history-list"\)/u);
  assert.match(source, /list\.getAttribute\("aria-busy"\)/u);
  assert.match(source, /list\.querySelectorAll\("button\[data-thread-id\]"\)\.length/u);
  assert.match(source, /const shouldShowSpinner = busy && rowCount === 0/u);
  assert.match(source, /if \(shouldShowSpinner\)/u);
  assert.match(source, /!visible\(spinner\)/u);
  assert.match(source, /spinner\.parentElement !== heading/u);
  assert.match(source, /spinner\.closest\("\.ja-navigation-history-list"\) !== null/u);
  assert.match(
    source,
    /if \(busy\) historyLoadingViolations\.unexpectedSpinnerWhileCachedBusy \+= 1/u,
  );
  assert.match(source, /else historyLoadingViolations\.unexpectedSpinnerWhileIdle \+= 1/u);
  assert.doesNotMatch(source, /\.ja-navigation-thread-(?:status|loading)/u);
  assert.match(source, /workspaceSwitchFailureReport\(partialReport, error\)/u);
}
test("history loading DOM 断言按空忙碌/缓存与空闲分支执行", assertHistoryLoadingDomContract);

/** @author kongweiguang 失败序列化只附加 FAIL 身份并保留所有完成采样，不捏造分支观察。 */
test("workspaceSwitchFailureReport 保留部分证据且不补造未观测状态", () => {
  const partial = validReport();
  partial.correctness.historyLoading.observations.busyEmpty = 0;
  const failed = workspaceSwitchFailureReport(partial, new Error("after gate failed"));
  assert.equal(failed.verdict, "FAIL");
  assert.equal(failed.error, "runner_failed_after_measurement");
  assert.deepEqual(failed.metrics, partial.metrics);
  assert.deepEqual(failed.correctness.historyLoading, partial.correctness.historyLoading);
  assert.deepEqual(failed.execution.tauri, partial.execution.tauri);
  assert.equal("passedBranches" in failed.correctness.historyLoading, false);
});

/** 基线比较仅读取指标摘要，不把用户路径或历史正文带入 runner 结构。 */
test("readBaselineReport 读取 metrics 并拒绝非本 runner 报告", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-workspace-switch-baseline-"));
  context.after(() => rm(root, { recursive: true, force: true }));
  const path = join(root, "baseline.json");
  await writeFile(path, JSON.stringify(validReport("baseline")), "utf8");
  const baseline = await readBaselineReport(path);
  assert.deepEqual(baseline.metrics.aToB, { p50Ms: 120, p95Ms: 180 });
  await writeFile(path, JSON.stringify({ metrics: {} }), "utf8");
  await assert.rejects(() => readBaselineReport(path), /no workspace switch metrics/u);
});

/** Maven 版本核对必须使用 JDK25；测试只固定非法 home 的失败合同，不启动构建。 */
test("validateMavenToolchain 不静默回退默认 JDK", async () => {
  await assert.rejects(
    () => validateMavenToolchain(join(tmpdir(), "missing-ja-jdk-25")),
    /ENOENT|EINVAL|cannot find|not found/u,
  );
});
