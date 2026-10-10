// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { createContext, runInContext } from "node:vm";
import {
  CODING_REVIEW_AI_NARROW_MATRIX,
  CODING_REVIEW_AI_PALETTES,
  CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX,
  REVIEW_DRIVER_STAGES,
  REVIEW_REDESIGN_MATRIX,
  normalizeDevicePixelRatio,
  readReviewThreadSurfaceState,
  readCodingReviewDiffReadiness,
  runReviewRedesignWebView2,
  reviewRedesignVerdict,
  installReviewInvokeProbe,
  reviewContentBoxWidth,
  reviewLayoutForContentWidth,
  sanitizeReviewThreadFailureDiagnostic,
  validateCodingReviewDiffReadiness,
  summarizeReviewCatalogSources,
  validateReviewRedesignReport,
} from "./review-redesign-webview2-driver.mjs";
import {
  assertOwnedTemporaryPath,
  buildLaunchEnvironment,
  classifyOwnedProcessSnapshotFailure,
  createReviewDriverHooks,
  createReviewGitFixture,
  parseArguments,
  projectOwnedProcessSnapshot,
  readOwnedProcessSnapshot,
  readOwnedTauriExecutableIdentity,
  selectOwnedTauriProcess,
  validateToolchain,
  verifyOwnedTauriExecutableIdentity,
  writeIsolatedSettings,
  writeTauriOverlay,
} from "./review-redesign-production.mjs";

const execFileAsync = promisify(execFile);

/** @author kongweiguang 构造可控 Node 子树并可将一层替换成临时同名 Desktop 副本，以真进程验证扫描边界且不启动产品。 */
function startOwnedProcessTree(
  depth,
  lifetimeMs,
  { desktopAtDepth = -1, desktopExecutablePath = "" } = {},
) {
  const source = `
    const { spawn } = require("node:child_process");
    const [depthText, sourceText, lifetimeText, desktopDepthText, desktopPath, nodePath] = process.argv.slice(1);
    const depth = Number(depthText);
    process.stdout.write("OWNED_TEST_PROCESS_READY=" + process.pid + ":" + process.ppid + "\\n");
    if (depth > 0) {
      const childDepth = depth - 1;
      const childExecutable = desktopPath !== "" && childDepth === Number(desktopDepthText) ? desktopPath : nodePath;
      spawn(childExecutable, ["-e", sourceText, String(childDepth), sourceText, lifetimeText, desktopDepthText, desktopPath, nodePath], { windowsHide: true, stdio: ["ignore", "inherit", "ignore"] });
    }
    setTimeout(() => {}, Number(lifetimeText));
  `;
  const launcher = spawn(
    process.execPath,
    [
      "-e",
      source,
      String(depth),
      source,
      String(lifetimeMs),
      String(desktopAtDepth),
      desktopExecutablePath,
      process.execPath,
    ],
    { windowsHide: true, stdio: ["ignore", "pipe", "ignore"] },
  );
  let readyCount = 0;
  let output = "";
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error("owned_test_process_tree_start_timeout")),
      5_000,
    );
    launcher.stdout.on("data", (chunk) => {
      output += chunk.toString("utf8");
      const processFacts = [...output.matchAll(/OWNED_TEST_PROCESS_READY=(\d+):(\d+)/gu)];
      readyCount = processFacts.length;
      if (readyCount >= depth + 1) {
        clearTimeout(timer);
        resolve(
          processFacts.map(([, pid, parentPid]) => ({
            pid: Number(pid),
            parentPid: Number(parentPid),
          })),
        );
      }
    });
    launcher.once("error", () => {
      clearTimeout(timer);
      reject(new Error("owned_test_process_tree_start_failed"));
    });
    launcher.once("close", (code) => {
      if (readyCount < depth + 1) {
        clearTimeout(timer);
        reject(new Error(`owned_test_process_tree_exit_${code}`));
      }
    });
  });
  return { depth, launcher, ready };
}

/** Model the current Workbench DOM and Native probe without a browser or any extra Review IO. */
function readDiffReadinessForTest(diffCall, source = { kind: "uncommitted" }) {
  const workspaceId = "ws_0123456789abcdef";
  const revision = "a".repeat(64);
  const fileId = "b".repeat(64);
  const scopeLabel =
    source.kind === "uncommitted" ? "审阅范围：未提交" : `审阅范围：${source.refId}`;
  const layer = source.kind === "uncommitted" ? "untracked" : "comparison";
  const visibleElement = { getClientRects: () => [{}] };
  const trigger = {
    ...visibleElement,
    getAttribute: (name) => (name === "aria-label" ? scopeLabel : null),
  };
  const diff = {
    ...visibleElement,
    getAttribute: (name) =>
      name === "data-review-selected-file-id"
        ? fileId
        : name === "data-review-selected-layer"
          ? layer
          : null,
    querySelector: (selector) => (selector === ".ja-review-diff-header" ? visibleElement : null),
    querySelectorAll: () => [],
  };
  const shell = {
    querySelector: (selector) => (selector === ".ja-review-diff" ? diff : null),
  };
  const session = {
    querySelector: (selector) =>
      selector === ".ja-review-source-trigger"
        ? trigger
        : selector.includes("[data-ja-review-shell]")
          ? shell
          : null,
  };
  const calls = [
    { command: "ja_review_catalog", phase: "resolved", workspaceId },
    {
      command: "ja_review_snapshot",
      phase: "resolved",
      workspaceId,
      source,
      resultRevision: revision,
    },
    {
      command: "ja_review_file_diff",
      phase: "resolved",
      workspaceId,
      source,
      revision,
      fileId,
      layer: null,
      resultFileId: fileId,
      resultLayer: layer,
      resultRevision: revision,
      ...diffCall,
    },
  ];
  const context = createContext({
    document: { querySelector: () => session },
    getComputedStyle: () => ({ display: "block", visibility: "visible" }),
    __JA_REVIEW_REDESIGN_CALLS__: calls,
  });
  const predicate = runInContext(`(${readCodingReviewDiffReadiness.toString()})`, context);
  return predicate({ scopeLabel, source });
}

/** 构造包含真实 Runtime RPC 与 React ready 投影证据的最小 Git/Review 报告。 */
function validReport(scope = "git") {
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    gitAdapter: "real_native",
    scope,
    runtimeReadiness: {
      command: "ja_runtime_state",
      status: "ready",
      generation: 1,
      serverInstanceIdPresent: true,
      projectedReady: true,
    },
    verdict: scope === "full" ? "PASS" : "NOT VERIFIED",
    gitReview: {
      status: "passed",
      hiddenSnapshotDelta: 0,
      matrix: REVIEW_REDESIGN_MATRIX.map((frame) => ({
        requestedContentWidth: frame.width,
        actualContentWidth: frame.width,
        actualOuterWidth: frame.width + 2,
        layout: reviewLayoutForContentWidth(frame.width),
        themeMode: frame.theme,
        devicePixelRatio: frame.devicePixelRatio,
        reducedMotion: frame.reducedMotion,
        dpiEvidence: "cdp_emulated",
      })),
    },
    turnReview: scope === "full" ? { status: "passed" } : { status: "not_verified" },
  };
}

/** @author kongweiguang 通过最小 DOM 双替身验证会话身份读取，不把空 Thread 的缺失 Timeline 当作未就绪。 */
function readThreadSurfaceStateForTest({
  threadId = "thr_test123",
  scopes = ["ws_test:thr_test123"],
  bodyState = "empty",
  historyBusy = false,
  timelineIds = [],
} = {}) {
  const selected =
    threadId === null
      ? null
      : {
          getAttribute(name) {
            return name === "data-thread-id" ? threadId : null;
          },
        };
  const history = {
    getAttribute(name) {
      if (name === "aria-busy") return historyBusy ? "true" : "false";
      return null;
    },
    querySelector() {
      return selected;
    },
  };
  const sessions = scopes.map((scope) => ({
    getAttribute(name) {
      return name === "data-thread-workbench-scope" ? scope : null;
    },
  }));
  const timelines = timelineIds.map((id) => ({
    getAttribute(name) {
      return name === "data-thread-id" ? id : null;
    },
  }));
  const document = {
    querySelector(selector) {
      if (selector === '[aria-label="最近对话列表"]') return history;
      if (selector === ".ja-conversation-body") {
        return { getAttribute: (name) => (name === "data-state" ? bodyState : null) };
      }
      return null;
    },
    querySelectorAll(selector) {
      if (selector.startsWith(".ja-thread-workbench-session")) return sessions;
      if (selector === ".ja-chat-timeline[data-thread-id]") return timelines;
      return [];
    },
  };
  const context = createContext({ document });
  return runInContext(
    `(${readReviewThreadSurfaceState.toString()})({ includeUnready: true })`,
    context,
  );
}

/** @author kongweiguang 固定空 Thread 的合法 UI 前置条件及真实侧栏/Workbench identity 对齐边界。 */
test("Review accepts a real empty Thread when its selected row matches the visible Workbench scope", () => {
  const emptyThread = readThreadSurfaceStateForTest();
  assert.equal(emptyThread.ready, true);
  assert.equal(emptyThread.conversationBodyState, "empty");
  assert.equal(emptyThread.timelineMountedForSelectedThread, false);
  assert.equal("threadId" in emptyThread, false);

  const mismatchedScope = readThreadSurfaceStateForTest({ scopes: ["ws_test:thr_other"] });
  assert.equal(mismatchedScope.ready, false);
  assert.equal(mismatchedScope.workbenchSessionMatchesSelectedThread, false);

  const unselected = readThreadSurfaceStateForTest({ threadId: null });
  assert.equal(unselected.ready, false);
  assert.equal(unselected.selectedThreadIdValid, false);
});

/** @author kongweiguang 保证阶段和诊断字段闭集化，任意 ID、标题、正文、URL、凭据及错误原文均不进入失败证据。 */
test("Review failure diagnostics are finite, sanitized, and stage-scoped", () => {
  assert.ok(REVIEW_DRIVER_STAGES.includes("driver:thread_surface_ready"));
  const diagnostic = sanitizeReviewThreadFailureDiagnostic({
    stage: "driver:thread_surface_ready",
    code: "THREAD_SURFACE_NOT_READY",
    threadId: "thr-secret",
    title: "private title",
    payload: "private body",
    url: "https://private.example/?token=secret",
    error: "private error text",
    surface: readThreadSurfaceStateForTest(),
    screenshotCaptured: true,
  });
  assert.equal(diagnostic.stage, "driver:thread_surface_ready");
  assert.equal(diagnostic.screenshot, "screenshots/thread-readiness-failure.png");
  assert.equal(JSON.stringify(diagnostic).includes("secret"), false);
  assert.equal(JSON.stringify(diagnostic).includes("private"), false);
  assert.equal(
    sanitizeReviewThreadFailureDiagnostic({
      stage: "driver:unbounded-stage",
      code: "THREAD_SURFACE_NOT_READY",
    }),
    undefined,
  );
  assert.equal(
    sanitizeReviewThreadFailureDiagnostic({
      stage: "driver:thread_surface_ready",
      code: "private-error-code",
    }),
    undefined,
  );
});

/** @author kongweiguang 仅默认 Review driver 接收闭集诊断 hook，避免改变自定义场景 driver 的调用契约。 */
test("Review driver hooks are bounded to the default driver and sanitize diagnostics", () => {
  const customDriver = () => {};
  assert.deepEqual(
    createReviewDriverHooks(customDriver, false, () => assert.fail()),
    {},
  );
  assert.deepEqual(
    createReviewDriverHooks(runReviewRedesignWebView2, false, () => assert.fail()),
    {},
  );

  const seenStages = [];
  const seenDiagnostics = [];
  const hooks = createReviewDriverHooks(
    runReviewRedesignWebView2,
    true,
    (stage) => seenStages.push(stage),
    (diagnostic) => seenDiagnostics.push(diagnostic),
  );
  hooks.setStage("driver:thread_prepare");
  assert.throws(() => hooks.setStage("driver:private"), /closed/u);
  hooks.onFailureDiagnostic({
    stage: "driver:thread_surface_ready",
    code: "THREAD_SURFACE_NOT_READY",
    threadId: "thr-private",
    payload: "private body",
    surface: { ready: false },
  });
  hooks.onFailureDiagnostic({ stage: "driver:unknown", code: "unknown" });

  assert.deepEqual(seenStages, ["driver:thread_prepare"]);
  assert.equal(seenDiagnostics.length, 1);
  assert.equal(JSON.stringify(seenDiagnostics[0]).includes("private"), false);
});

/** @author kongweiguang full PASS 仅接受 Turn 生命周期 passed；可见标记和 Git-only 结果不能冒充闭环。 */
test("Review verdict requires a passed Turn lifecycle for full scope", () => {
  assert.equal(reviewRedesignVerdict("full", "passed"), "PASS");
  assert.equal(reviewRedesignVerdict("full", "observed"), "NOT VERIFIED");
  assert.equal(reviewRedesignVerdict("full", "not_verified"), "NOT VERIFIED");
  assert.equal(reviewRedesignVerdict("git", "passed"), "NOT VERIFIED");
});

/** @author kongweiguang 锁住Thread身份等待不扩为全轮预算，诊断回读与截图也必须受当前剩余deadline约束。 */
test("Review Thread readiness and failure capture keep bounded deadlines", async () => {
  const source = await readFile(
    new URL("./review-redesign-webview2-driver.mjs", import.meta.url),
    "utf8",
  );
  assert.match(source, /const THREAD_READY_TIMEOUT_MS = 30_000/u);
  assert.match(source, /timeout: Math\.min\(THREAD_READY_TIMEOUT_MS, timeout\(deadline\)\)/u);
  assert.match(source, /timeout: Math\.min\(3_000, timeout\(deadline\)\)/u);
});

/** Launch 参数必须显式声明 scope、证据目录和 debug JAR。 */
test("parseArguments 接受显式 launch 参数", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-review-evidence"),
    "--scope",
    "git",
    "--jar",
    join(tmpdir(), "ja-review.jar"),
  ]);
  assert.equal(parsed.scope, "git");
  assert.equal(parsed.fixture, "full");
  assert.equal(parsed.untrackedFiles, 1_800);
});

/** Native source switching needs only the catalog identities; contributor and file details stay out of diagnostics. */
test("review catalog evidence retains base/local ref kinds and commit IDs only", () => {
  const evidence = summarizeReviewCatalogSources({
    headCommitId: "abcdef0123456789",
    baseRefs: [
      { refId: "main", label: "main", kind: "base" },
      { refId: "feature", label: "feature", kind: "local" },
      { refId: "origin/main", label: "origin/main", kind: "remote" },
    ],
    commits: [{ commitId: "abcdef0123456789", subject: "private subject", author: "private" }],
    workspacePath: "C:\\private\\workspace",
  });
  assert.deepEqual(evidence, {
    headCommitId: "abcdef0123456789",
    refs: [
      { refId: "main", label: "main", kind: "base" },
      { refId: "feature", label: "feature", kind: "local" },
      { refId: "origin/main", label: "origin/main", kind: "remote" },
    ],
    commitIds: ["abcdef0123456789"],
  });
  assert.doesNotMatch(JSON.stringify(evidence), /private|subject|workspacePath/u);
});

/** Init-script probe must project Catalog results in the WebView realm without module closures. */
test("Review invoke probe keeps a resolved Native Catalog result resolved", async () => {
  const pageRealm = createContext({
    performance: { now: () => 17 },
  });
  runInContext(`(${installReviewInvokeProbe.toString()})()`, pageRealm);
  const result = {
    headCommitId: "abcdef0123456789",
    baseRefs: [
      { refId: "main", label: "main", kind: "base" },
      { refId: "feature", label: "feature", kind: "local" },
    ],
    commits: [{ commitId: "1234567890abcdef", subject: "private subject" }],
    workspacePath: "C:\\private\\workspace",
  };
  const returned = await pageRealm.__JA_E2E_NATIVE_INVOKE_PROBE__(
    { command: "ja_review_catalog", args: { input: { workspaceId: "workspace-id" } } },
    async () => result,
  );

  assert.equal(returned, result);
  assert.deepEqual(JSON.parse(JSON.stringify(pageRealm.__JA_REVIEW_REDESIGN_CALLS__)), [
    {
      command: "ja_review_catalog",
      phase: "resolved",
      workspaceId: "workspace-id",
      source: null,
      expectedSnapshotRevision: null,
      revision: null,
      operationId: null,
      fileId: null,
      layer: null,
      elapsedMs: 0,
      catalogSources: {
        headCommitId: "abcdef0123456789",
        refs: [
          { refId: "main", label: "main", kind: "base" },
          { refId: "feature", label: "feature", kind: "local" },
        ],
        commitIds: ["1234567890abcdef"],
      },
    },
  ]);
  assert.deepEqual(
    JSON.parse(JSON.stringify(pageRealm.__JA_REVIEW_REDESIGN_COUNTS__.ja_review_catalog)),
    { start: 1, resolved: 1, rejected: 0 },
  );
});

/** Native launch 必须锁定可执行文件摘要，并与 JVM JAR 路径互斥，防止产物边界含混。 */
test("parseArguments 为 Native executable 要求固定 SHA-256 且拒绝 JAR 混用", () => {
  const executable = join(tmpdir(), "ja-app-server.exe");
  const digest = "a".repeat(64);
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-review-native-evidence"),
    "--scope",
    "full",
    "--native-executable",
    executable,
    "--expected-native-sha256",
    digest.toUpperCase(),
  ]);
  assert.equal(parsed.nativeExecutable, executable);
  assert.equal(parsed.expectedNativeSha256, digest);
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        join(tmpdir(), "ja-review-native-evidence"),
        "--scope",
        "full",
        "--jar",
        join(tmpdir(), "ja-app-server.jar"),
        "--native-executable",
        executable,
        "--expected-native-sha256",
        digest,
      ]),
    /mutually exclusive/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        join(tmpdir(), "ja-review-native-evidence"),
        "--scope",
        "full",
        "--native-executable",
        executable,
      ]),
    /requires --expected-native-sha256/u,
  );
});

/** Native 预检只验证精确文件摘要与 Node/pnpm，不要求 JVM 并阻止校验与运行产物不一致。 */
test("validateToolchain verifies the selected Native fingerprint without a JDK", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-review-native-fingerprint-"));
  context.after(async () => rm(root, { recursive: true, force: true }));
  const executable = join(root, "ja-app-server.exe");
  const content = "native sidecar fixture";
  await writeFile(executable, content, "utf8");
  const expected = createHash("sha256").update(content).digest("hex");
  const accepted = await validateToolchain({
    nativeExecutable: executable,
    expectedNativeSha256: expected,
  });
  assert.equal(accepted.java, undefined);
  assert.equal(accepted.nativeSha256, expected);
  await assert.rejects(
    validateToolchain({
      nativeExecutable: executable,
      expectedNativeSha256: "0".repeat(64),
    }),
    /SHA-256/u,
  );
});

/** 隔离 runner 生成的新配置只保留 Provider 网络和模型能力，不写任务预算。 */
test("writeIsolatedSettings omits removed task budgets", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-review-rounds-test-"));
  context.after(async () => rm(root, { recursive: true, force: true }));
  await writeIsolatedSettings(join(root, "five"), "http://127.0.0.1:1234/v1");
  const fiveRounds = await readFile(join(root, "five", "config.toml"), "utf8");
  assert.doesNotMatch(fiveRounds, /turn_limits|max_model_rounds|max_tool_calls|wall_timeout_ms/u);
  assert.match(fiveRounds, /^request_timeout_ms = 30000$/mu);

  await writeIsolatedSettings(join(root, "default"), "http://127.0.0.1:1234/v1");
  const defaultRounds = await readFile(join(root, "default", "config.toml"), "utf8");
  assert.doesNotMatch(
    defaultRounds,
    /turn_limits|max_model_rounds|max_tool_calls|wall_timeout_ms/u,
  );
});

/** 官方 EdgeDriver 路径是显式 launch 选择，不能从 PATH 猜测或与 attach 混用。 */
test("parseArguments 接受 EdgeDriver launch 并拒绝 attach 混用", () => {
  const evidenceDirectory = join(tmpdir(), "ja-review-evidence");
  const edgeDriver = join(tmpdir(), "msedgedriver.exe");
  const launch = parseArguments([
    "--evidence-directory",
    evidenceDirectory,
    "--scope",
    "git",
    "--jar",
    join(tmpdir(), "ja-review.jar"),
    "--edge-driver",
    edgeDriver,
  ]);
  assert.equal(launch.edgeDriver, edgeDriver);
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        evidenceDirectory,
        "--scope",
        "git",
        "--cdp-endpoint",
        "http://127.0.0.1:9222",
        "--workspace-root",
        join(tmpdir(), "ja-review-workspace"),
        "--owned-profile-root",
        join(tmpdir(), "ja-review-profile"),
        "--frontend-port",
        "1427",
        "--edge-driver",
        edgeDriver,
      ]),
    /does not accept --edge-driver/u,
  );
});

/** Attach 参数不得从 CDP endpoint 猜测前端端口。 */
test("parseArguments 强制 attach frontend port", () => {
  const common = [
    "--evidence-directory",
    join(tmpdir(), "ja-review-evidence"),
    "--scope",
    "git",
    "--cdp-endpoint",
    "http://127.0.0.1:9222",
    "--workspace-root",
    join(tmpdir(), "ja-review-workspace"),
    "--owned-profile-root",
    join(tmpdir(), "ja-review-profile"),
  ];
  assert.throws(() => parseArguments(common), /--frontend-port/u);
  const parsed = parseArguments([...common, "--frontend-port", "1427"]);
  assert.equal(parsed.frontendPort, 1427);
});

/** 清理与 attach 只能指向 OS temp 下的具体子目录。 */
test("assertOwnedTemporaryPath 拒绝 broad target", () => {
  assert.throws(() => assertOwnedTemporaryPath(tmpdir(), "root"), /must be a child/u);
  assert.throws(() => assertOwnedTemporaryPath(process.cwd(), "repo"), /must be a child/u);
  assert.equal(
    assertOwnedTemporaryPath(join(tmpdir(), "ja-review-owned", "workspace"), "workspace"),
    join(tmpdir(), "ja-review-owned", "workspace"),
  );
});

/** 小 fixture 仍使用真实 Git 覆盖冲突、部分暂存、特殊路径和未跟踪压力。 */
test("createReviewGitFixture 构造真实分层 Git 状态", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-review-fixture-test-"));
  context.after(async () => rm(root, { recursive: true, force: true }));
  const workspace = join(root, "workspace");
  const facts = await createReviewGitFixture(workspace, {
    fixture: "full",
    ignoredFiles: 3,
    untrackedFiles: 5,
  });
  const { stdout } = await execFileAsync(
    "git.exe",
    ["-c", "core.quotepath=false", "status", "--porcelain=v2", "--untracked-files=all"],
    {
      cwd: workspace,
      windowsHide: true,
    },
  );
  assert.equal(facts.conflict, true);
  assert.equal(facts.partialStaging, true);
  assert.match(stdout, /^u /mu);
  assert.match(stdout, /^1 MM .*partially-staged\.ts$/mu);
  assert.match(stdout, /src\/空 格\/delta\.ts/u);
});

/** Debug launch 环境隔离应用数据并让 Java 25 解析优先于宿主 PATH。 */
test("buildLaunchEnvironment 固定隔离目录和 JDK 25", () => {
  const root = join(tmpdir(), "ja-review-env");
  const directories = {
    settings: join(root, "profile"),
    roaming: join(root, "roaming"),
    local: join(root, "local"),
    runtime: join(root, "runtime"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
  };
  const java = join(root, "jdk-25", "bin", "java.exe");
  const env = buildLaunchEnvironment({
    directories,
    java,
    jar: join(root, "ja-app-server.jar"),
    frontendPort: 1427,
    cdpPort: 9227,
    cargoTargetDirectory: join(root, "cargo-target"),
  });
  assert.equal(env.USERPROFILE, directories.settings);
  assert.equal(env.JA_E2E_RUNTIME_ROOT, directories.runtime);
  assert.equal(env.JAVA_HOME, dirname(dirname(java)));
  assert.ok(env.PATH.startsWith(`${dirname(java)};`));
  assert.equal(env.JAVA_TOOL_OPTIONS, undefined);
  assert.equal(env.JDK_JAVA_OPTIONS, undefined);
  assert.equal(env._JAVA_OPTIONS, undefined);
  assert.match(
    env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS,
    /--remote-debugging-address=127\.0\.0\.1/u,
  );
  assert.match(env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS, /--remote-debugging-port=9227/u);
});

/** Native 模式必须清除所有 Java/JAR 选择器，避免测试静默降级到 JVM sidecar。 */
test("buildLaunchEnvironment 仅选择 Native resource runtime", () => {
  const root = join(tmpdir(), "ja-review-native-env");
  const directories = {
    settings: join(root, "profile"),
    roaming: join(root, "roaming"),
    local: join(root, "local"),
    runtime: join(root, "runtime"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
  };
  const env = buildLaunchEnvironment({
    directories,
    java: undefined,
    jar: undefined,
    nativeExecutable: join(root, "ja-app-server.exe"),
    frontendPort: 1427,
    cdpPort: 9227,
    cargoTargetDirectory: join(root, "cargo-target"),
  });
  assert.equal(env.JA_E2E_RUNTIME_MODE, "production");
  assert.equal(env.JA_E2E_RUNTIME_ROOT, directories.runtime);
  assert.equal(env.JAVA_HOME, undefined);
  assert.equal(env.JA_JAVA25_HOME, undefined);
  assert.equal(env.JA_DEBUG_JAVA, undefined);
  assert.equal(env.JA_DEBUG_JAR, undefined);
  assert.equal(env.JA_E2E_APP_SERVER_JAR, undefined);
});

/** @author kongweiguang 只从 launcher 的 PID 后代选择唯一桌面进程，不能被用户已有同名 Ja 干扰。 */
test("selectOwnedTauriProcess 只接纳本轮 launcher 后代", () => {
  const processes = [
    {
      ProcessId: 100,
      ParentProcessId: 1,
      ExecutablePath: "C:\\Windows\\System32\\cmd.exe",
    },
    {
      ProcessId: 200,
      ParentProcessId: 100,
      ExecutablePath: "C:\\Program Files\\nodejs\\node.exe",
    },
    {
      ProcessId: 300,
      ParentProcessId: 200,
      ExecutablePath: "C:\\Rust\\bin\\cargo.exe",
    },
    {
      ProcessId: 400,
      ParentProcessId: 300,
      ExecutablePath: "C:\\repo\\target\\debug\\ja-desktop.exe",
    },
    {
      ProcessId: 500,
      ParentProcessId: 1,
      ExecutablePath: "C:\\other\\target\\debug\\ja-desktop.exe",
    },
  ];
  assert.deepEqual(selectOwnedTauriProcess(processes, 100), processes[3]);
  const nestedDesktop = {
    ProcessId: 401,
    ParentProcessId: 400,
    ExecutablePath: "C:\\repo\\target\\debug\\ja-desktop.exe",
  };
  assert.deepEqual(selectOwnedTauriProcess([...processes, nestedDesktop], 100), processes[3]);
  assert.throws(() => selectOwnedTauriProcess(processes, 900), /owned_tauri_process_missing/u);
  assert.throws(
    () =>
      selectOwnedTauriProcess(
        [
          ...processes,
          {
            ProcessId: 350,
            ParentProcessId: 100,
            ExecutablePath: "C:\\repo\\other\\ja-desktop.exe",
          },
        ],
        100,
      ),
    /owned_tauri_process_ambiguous/u,
  );
  assert.throws(
    () => selectOwnedTauriProcess([...processes, { ...processes[3], ProcessId: 401 }], 100),
    /owned_tauri_process_ambiguous/u,
  );
  assert.throws(
    () =>
      selectOwnedTauriProcess([{ ...processes[0], ProcessId: 100 }], Number.MAX_SAFE_INTEGER + 1),
    /owned_tauri_process_snapshot_invalid/u,
  );
  assert.throws(
    () => selectOwnedTauriProcess([{ ProcessId: "400", ParentProcessId: 300 }], 100),
    /owned_tauri_process_snapshot_invalid/u,
  );
  assert.throws(
    () =>
      selectOwnedTauriProcess(
        [
          {
            ProcessId: 0,
            ParentProcessId: 100,
            ExecutablePath: processes[3].ExecutablePath,
          },
        ],
        100,
      ),
    /owned_tauri_process_snapshot_invalid/u,
  );
  assert.throws(
    () =>
      selectOwnedTauriProcess(
        [{ ProcessId: 200, ParentProcessId: 100, ExecutablePath: null }],
        100,
      ),
    /owned_tauri_process_missing/u,
  );
  assert.throws(
    () => selectOwnedTauriProcess([...processes, { ...processes[3], ParentProcessId: 400 }], 100),
    /owned_tauri_process_snapshot_invalid/u,
  );
});

/** @author kongweiguang 全机最小 inventory 只投影 launcher 后代；固定测试同级外部进程及 Desktop 子树不会进入结果。 */
test("projectOwnedProcessSnapshot excludes unrelated branches and descendants below Desktop", () => {
  const processes = [
    { ProcessId: 0, ParentProcessId: 0, ExecutablePath: null },
    { ProcessId: 100, ParentProcessId: 1, ExecutablePath: "C:\\Windows\\cmd.exe" },
    { ProcessId: 200, ParentProcessId: 100, ExecutablePath: "C:\\node.exe" },
    { ProcessId: 201, ParentProcessId: 200, ExecutablePath: "C:\\target\\ja-desktop.exe" },
    { ProcessId: 202, ParentProcessId: 201, ExecutablePath: "C:\\WebView2\\msedge.exe" },
    { ProcessId: 300, ParentProcessId: 100, ExecutablePath: "C:\\cargo.exe" },
    { ProcessId: 400, ParentProcessId: 1, ExecutablePath: "C:\\other\\node.exe" },
    { ProcessId: 401, ParentProcessId: 400, ExecutablePath: "C:\\other\\ja-desktop.exe" },
  ];

  const snapshot = projectOwnedProcessSnapshot(processes, 100);
  assert.deepEqual(
    snapshot.map(({ ProcessId }) => ProcessId),
    [200, 300, 201],
  );
  assert.deepEqual(selectOwnedTauriProcess(snapshot, 100), processes[3]);
  assert.equal(
    snapshot.some(({ ProcessId }) => ProcessId === 400 || ProcessId === 401),
    false,
  );
  assert.equal(
    snapshot.some(({ ProcessId }) => ProcessId === 202),
    false,
  );
});

/** @author kongweiguang 同一 inventory 的 PID 冲突与 launcher 闭包环必须失败关闭，不能靠 visited 静默截断。 */
test("projectOwnedProcessSnapshot rejects duplicate PIDs and cycles in the owned closure", () => {
  assert.throws(
    () =>
      projectOwnedProcessSnapshot(
        [
          { ProcessId: 200, ParentProcessId: 100, ExecutablePath: "C:\\node.exe" },
          { ProcessId: 200, ParentProcessId: 900, ExecutablePath: "C:\\other.exe" },
        ],
        100,
      ),
    /owned_tauri_process_snapshot_invalid/u,
  );
  assert.throws(
    () =>
      projectOwnedProcessSnapshot(
        [
          { ProcessId: 200, ParentProcessId: 100, ExecutablePath: "C:\\node.exe" },
          { ProcessId: 100, ParentProcessId: 200, ExecutablePath: "C:\\cmd.exe" },
        ],
        100,
      ),
    /owned_tauri_process_tree_invalid/u,
  );
});

/** @author kongweiguang 深 launcher 链使用索引式 BFS，保持线性投影且不把 Desktop 外子树算入长度。 */
test("projectOwnedProcessSnapshot projects a twenty-level Node chain", () => {
  const processes = Array.from({ length: 20 }, (_, index) => ({
    ProcessId: 200 + index,
    ParentProcessId: index === 0 ? 100 : 199 + index,
    ExecutablePath: "C:\\Program Files\\nodejs\\node.exe",
  }));

  const snapshot = projectOwnedProcessSnapshot(processes, 100);
  assert.equal(snapshot.length, 20);
  assert.equal(snapshot.at(-1)?.ProcessId, 219);
});

/** @author kongweiguang 全机 inventory 与 launcher 后代上限均失败关闭，不截断结果伪装成完整身份。 */
test("projectOwnedProcessSnapshot enforces the inventory limit and preserves missing executable paths", () => {
  const oversized = Array.from({ length: 16_385 }, (_, index) => ({
    ProcessId: index + 1,
    ParentProcessId: 0,
    ExecutablePath: null,
  }));
  assert.throws(
    () => projectOwnedProcessSnapshot(oversized, 100),
    /owned_tauri_process_inventory_limit/u,
  );
  const nullPath = projectOwnedProcessSnapshot(
    [{ ProcessId: 200, ParentProcessId: 100, ExecutablePath: null }],
    100,
  );
  assert.equal(nullPath.length, 1);
  assert.throws(() => selectOwnedTauriProcess(nullPath, 100), /owned_tauri_process_missing/u);
});

/** @author kongweiguang 文件身份必须来自真实临时目标内的 exe，并在 driver 前后核对尺寸、哈希及稳定路径。 */
test("readOwnedTauriExecutableIdentity captures and rechecks an in-target executable", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-owned-tauri-identity-"));
  context.after(() => rm(root, { recursive: true, force: true }));
  const target = join(root, "cargo-target");
  const executablePath = join(target, "debug", "ja-desktop.exe");
  const contents = Buffer.from("owned desktop executable fixture");
  await mkdir(join(target, "debug"), { recursive: true });
  await writeFile(executablePath, contents);
  const processes = [
    { ProcessId: 100, ParentProcessId: 4, ExecutablePath: "C:\\Windows\\System32\\cmd.exe" },
    { ProcessId: 200, ParentProcessId: 100, ExecutablePath: executablePath },
  ];

  const identity = await readOwnedTauriExecutableIdentity(processes, 100, target);
  assert.equal(identity.pid, 200);
  assert.equal(identity.launcherPid, 100);
  assert.equal(identity.executablePath, executablePath);
  assert.equal(identity.sizeBytes, contents.byteLength);
  assert.equal(identity.sha256, createHash("sha256").update(contents).digest("hex"));
  await verifyOwnedTauriExecutableIdentity(identity, target);

  const sameSizeReplacement = Buffer.from("owned desktop executable fixture");
  sameSizeReplacement[0] ^= 1;
  await writeFile(executablePath, sameSizeReplacement);
  await assert.rejects(
    verifyOwnedTauriExecutableIdentity(identity, target),
    /owned Tauri executable hash changed/u,
  );
  await writeFile(executablePath, Buffer.from("replaced binary"));
  await assert.rejects(
    verifyOwnedTauriExecutableIdentity(identity, target),
    /owned Tauri executable size changed/u,
  );
});

/** @author kongweiguang 任何缺失、越界、非法 PID/path 或不可读文件都关闭身份接纳且不触碰临时现场。 */
test("readOwnedTauriExecutableIdentity fails closed without taking cleanup ownership", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-owned-tauri-fail-"));
  context.after(() => rm(root, { recursive: true, force: true }));
  const target = join(root, "cargo-target");
  const outsidePath = join(root, "outside", "ja-desktop.exe");
  await mkdir(join(target, "debug"), { recursive: true });
  await mkdir(join(root, "outside"), { recursive: true });
  await writeFile(outsidePath, "outside target");
  const outsideTree = [
    { ProcessId: 100, ParentProcessId: 1, ExecutablePath: "C:\\Windows\\System32\\cmd.exe" },
    { ProcessId: 200, ParentProcessId: 100, ExecutablePath: outsidePath },
  ];
  await assert.rejects(
    readOwnedTauriExecutableIdentity(outsideTree, 100, target),
    /owned_tauri_executable_outside_target/u,
  );
  await assert.rejects(
    readOwnedTauriExecutableIdentity(
      [{ ProcessId: 200, ParentProcessId: 100, ExecutablePath: "ja-desktop.exe" }],
      100,
      target,
    ),
    /owned_tauri_executable_path_invalid/u,
  );
  await assert.rejects(
    readOwnedTauriExecutableIdentity(outsideTree, 100, join(root, "missing-target")),
    /owned_tauri_executable_unreadable/u,
  );
  assert.equal(await readFile(outsidePath, "utf8"), "outside target");

  const malformedIdentity = {
    pid: 0,
    launcherPid: 100,
    executablePath: outsidePath,
    sha256: "0".repeat(64),
    sizeBytes: 1,
  };
  await assert.rejects(
    verifyOwnedTauriExecutableIdentity(malformedIdentity, target),
    /owned_tauri_process_identity_invalid/u,
  );
});

/** @author kongweiguang 快照只请求 PID 关系和真实路径字段，避免诊断过程读取进程命令行或敏感环境。 */
test("owned Tauri process snapshot uses a bounded identity-only query", async () => {
  const source = await readFile(
    new URL("./review-redesign-production.mjs", import.meta.url),
    "utf8",
  );
  const start = source.indexOf("export async function readOwnedProcessSnapshot");
  const end = source.indexOf("export function projectOwnedProcessSnapshot", start);
  const snapshotQuery = source.slice(start, end);
  assert.match(snapshotQuery, /"pwsh\.exe"/u);
  assert.match(snapshotQuery, /Get-CimInstance -ClassName Win32_Process/u);
  assert.match(snapshotQuery, /-Property ProcessId,ParentProcessId,ExecutablePath/u);
  assert.match(source, /const MAX_PROCESS_SNAPSHOT_INVENTORY = 16_384/u);
  assert.match(snapshotQuery, /\$inventoryCount -gt \$\{MAX_PROCESS_SNAPSHOT_INVENTORY\}/u);
  assert.match(snapshotQuery, /PROCESS_INVENTORY_LIMIT/u);
  assert.match(snapshotQuery, /\$childrenByParent/u);
  assert.match(snapshotQuery, /if \(-not \$desktopRoot\) \{ \$pending\.Enqueue\(\$childPid\) \}/u);
  assert.match(snapshotQuery, /ConvertTo-Json -InputObject @\(\$descendants\)/u);
  assert.doesNotMatch(snapshotQuery, /ConvertTo-Json -InputObject @\(\$inventory\)/u);
  assert.match(snapshotQuery, /timeout: 10_000, maxBuffer: 4 \* 1024 \* 1024/u);
  assert.doesNotMatch(snapshotQuery, /-Filter\s/u);
  assert.doesNotMatch(snapshotQuery, /CommandLine|Environment|Get-Process/u);
});

/** @author kongweiguang 超时、缓冲上限、受控 CIM 标记和进程退出码必须归入安全闭集，原始错误不进入报告。 */
test("classifyOwnedProcessSnapshotFailure keeps only safe failure categories", () => {
  const cases = [
    [{ killed: true, code: null }, "timeout"],
    [{ code: "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" }, "max_buffer"],
    [{ code: "ENOENT", message: "C:\\private\\commandline" }, "powershell_unavailable"],
    [{ code: 31, stderr: "JA_PROCESS_SNAPSHOT:CIM_QUERY_FAILED" }, "query_failed"],
    [{ code: 32, stderr: "JA_PROCESS_SNAPSHOT:PROCESS_TREE_LIMIT" }, "tree_limit"],
    [{ code: 34, stderr: "JA_PROCESS_SNAPSHOT:PROCESS_INVENTORY_LIMIT" }, "inventory_limit"],
    [{ code: 35, stderr: "JA_PROCESS_SNAPSHOT:PROCESS_TREE_INVALID" }, "tree_invalid"],
    [{ code: 33, stderr: "JA_PROCESS_SNAPSHOT:SERIALIZATION_FAILED" }, "serialization_failed"],
    [{ code: 17, stderr: "C:\\private\\commandline" }, "exit_17"],
    [{ code: "UNKNOWN", message: "C:\\private\\commandline" }, "exec_failed"],
  ];
  for (const [failure, expected] of cases) {
    const classification = classifyOwnedProcessSnapshotFailure(failure);
    assert.equal(classification, expected);
    assert.doesNotMatch(classification, /C:\\private|commandline/u);
  }
});

/** @author kongweiguang 用短命真实多层子进程验证 CIM 查询、身份字段裁剪及 JSON 数组回环，不以空快照冒充通过。 */
test(
  "readOwnedProcessSnapshot reads an actual bounded Windows process tree",
  { skip: process.platform !== "win32", timeout: 25_000 },
  async (context) => {
    const tree = startOwnedProcessTree(5, 12_000);
    try {
      const expectedProcesses = await tree.ready;
      const startedAt = performance.now();
      const snapshot = await readOwnedProcessSnapshot(tree.launcher.pid);
      const elapsedMs = performance.now() - startedAt;
      context.diagnostic(`ownedProcessSnapshotElapsedMs=${elapsedMs.toFixed(1)}`);
      assert.ok(
        elapsedMs < 10_000,
        "controlled descendant query stays inside the production timeout",
      );
      assert.ok(snapshot.length >= tree.depth, "snapshot contains the expected descendant depth");
      assert.ok(snapshot.some((row) => row.ParentProcessId === tree.launcher.pid));
      for (const expected of expectedProcesses.filter((entry) => entry.pid !== tree.launcher.pid)) {
        assert.ok(
          snapshot.some(
            (row) => row.ProcessId === expected.pid && row.ParentProcessId === expected.parentPid,
          ),
          "snapshot includes every controlled descendant identity",
        );
      }
      assert.ok(
        snapshot.every(
          (row) =>
            typeof row.ExecutablePath === "string" &&
            row.ExecutablePath.length > 0 &&
            Number.isSafeInteger(row.ProcessId) &&
            Number.isSafeInteger(row.ParentProcessId),
        ),
      );
      assert.ok(
        snapshot.every(
          (row) =>
            JSON.stringify(Object.keys(row).sort()) ===
            JSON.stringify(["ExecutablePath", "ParentProcessId", "ProcessId"]),
        ),
      );
    } finally {
      const closed =
        tree.launcher.exitCode !== null ||
        (await Promise.race([
          new Promise((resolve) => tree.launcher.once("close", () => resolve(true))),
          new Promise((resolve) => setTimeout(() => resolve(false), 20_000)),
        ]));
      assert.equal(closed, true, "owned synthetic process tree exits naturally");
    }
  },
);

/** @author kongweiguang 以中文路径中的临时 Node 副本核实身份编码与 Desktop 子树裁剪，避免代码页损坏路径。 */
test(
  "readOwnedProcessSnapshot stops below the outermost Desktop root",
  { skip: process.platform !== "win32", timeout: 25_000 },
  async () => {
    const temporaryRoot = await mkdtemp(join(tmpdir(), "ja-process-snapshot-中文 桌面-"));
    const desktopExecutablePath = join(temporaryRoot, "ja-desktop.exe");
    let tree;
    try {
      await copyFile(process.execPath, desktopExecutablePath);
      tree = startOwnedProcessTree(5, 12_000, {
        desktopAtDepth: 1,
        desktopExecutablePath,
      });
      const expectedProcesses = await tree.ready;
      const snapshot = await readOwnedProcessSnapshot(tree.launcher.pid);
      const desktopRoot = snapshot.find(
        (row) =>
          typeof row.ExecutablePath === "string" &&
          row.ExecutablePath.toLowerCase().endsWith("\\ja-desktop.exe"),
      );
      assert.ok(desktopRoot, "snapshot retains the outermost Desktop identity");
      assert.equal(desktopRoot.ExecutablePath.toLowerCase(), desktopExecutablePath.toLowerCase());
      const desktopChild = expectedProcesses.find(
        (entry) => entry.parentPid === desktopRoot.ProcessId,
      );
      assert.ok(desktopChild, "the controlled process tree has a child below Desktop");
      assert.equal(
        snapshot.some((row) => row.ProcessId === desktopChild.pid),
        false,
        "Desktop application children are outside the main-window identity scan",
      );
      assert.equal(
        snapshot.some((row) => row.ParentProcessId === desktopRoot.ProcessId),
        false,
        "the scan does not add Desktop child PIDs to its next query frontier",
      );
    } finally {
      if (tree && tree.launcher.exitCode === null && tree.launcher.signalCode === null) {
        await new Promise((resolve) => tree.launcher.once("close", resolve));
      }
      await rm(temporaryRoot, { recursive: true, force: true });
    }
  },
);

/** @author kongweiguang 验证 sidecar 选择保持原样，并将可见 CDP 私有窗口移出宿主鼠标活动区。 */
test("writeTauriOverlay 仅映射显式选择的 Native sidecar", async (context) => {
  const root = await mkdtemp(join(tmpdir(), "ja-review-native-overlay-"));
  context.after(async () => rm(root, { recursive: true, force: true }));
  const directories = { runtime: join(root, "runtime") };
  await mkdir(directories.runtime, { recursive: true });
  const executable = join(root, "ja-app-server.exe");
  const nativeOverlay = await writeTauriOverlay(directories, 1427, false, false, executable);
  const nativeConfig = JSON.parse(await readFile(nativeOverlay, "utf8"));
  assert.deepEqual(nativeConfig.bundle.resources, {
    [executable]: "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
  });

  const jarOverlay = await writeTauriOverlay(directories, 1428, false);
  const jarConfig = JSON.parse(await readFile(jarOverlay, "utf8"));
  assert.equal(jarConfig.bundle, undefined);

  const hiddenOverlay = await writeTauriOverlay(directories, 1429, false, true);
  const hiddenConfig = JSON.parse(await readFile(hiddenOverlay, "utf8"));
  const hiddenWindow = hiddenConfig.app.windows[0];
  assert.deepEqual(
    {
      visible: hiddenWindow.visible,
      focus: hiddenWindow.focus,
      skipTaskbar: hiddenWindow.skipTaskbar,
      center: hiddenWindow.center,
      x: hiddenWindow.x,
      y: hiddenWindow.y,
    },
    {
      visible: true,
      focus: false,
      skipTaskbar: true,
      center: false,
      x: -10_000,
      y: -10_000,
    },
  );
});

/** EdgeDriver 模式只发布 runner ACK 合同，不得同时注入 Direct CDP 浏览器参数。 */
test("buildLaunchEnvironment 隔离 EdgeDriver 与 Direct CDP 环境", () => {
  const root = join(tmpdir(), "ja-review-edge-env");
  const directories = {
    settings: join(root, "profile"),
    roaming: join(root, "roaming"),
    local: join(root, "local"),
    runtime: join(root, "runtime"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
  };
  const env = buildLaunchEnvironment({
    directories,
    java: join(root, "jdk-25", "bin", "java.exe"),
    jar: join(root, "ja-app-server.jar"),
    frontendPort: 1427,
    cdpPort: 9227,
    cargoTargetDirectory: join(root, "cargo-target"),
    edgeDriver: join(root, "msedgedriver.exe"),
    edgeDriverPort: 47_111,
    edgeDriverSessionPath: join(root, "runtime", "edgedriver-session.json"),
    cargo: join(root, "cargo.exe"),
  });
  assert.equal(env.WEBVIEW2_USER_DATA_FOLDER, undefined);
  assert.equal(env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS, undefined);
  assert.equal(env.JA_E2E_EDGEDRIVER_PORT, "47111");
  assert.equal(env.JA_E2E_CARGO_COMMAND, join(root, "cargo.exe"));
  assert.equal(env.JA_E2E_WEBVIEW_DATA_DIR, join(root, "local", "main", "ja-review-edge-profile"));
});

/** 代表矩阵必须覆盖四个容器宽度、三个主题、三个 CDP 比例和减少动效。 */
test("REVIEW_REDESIGN_MATRIX 覆盖代表矩阵", () => {
  assert.equal(REVIEW_REDESIGN_MATRIX.length, 12);
  assert.deepEqual(
    new Set(REVIEW_REDESIGN_MATRIX.map(({ width }) => width)),
    new Set([360, 520, 760, 1000]),
  );
  assert.deepEqual(
    new Set(REVIEW_REDESIGN_MATRIX.map(({ theme }) => theme)),
    new Set(["light", "dark", "system"]),
  );
  assert.deepEqual(
    new Set(REVIEW_REDESIGN_MATRIX.map(({ devicePixelRatio }) => devicePixelRatio)),
    new Set([1, 1.25, 1.5]),
  );
  assert.ok(REVIEW_REDESIGN_MATRIX.some(({ reducedMotion }) => reducedMotion));
});

/** Layout 断点消费 ResizeObserver content box；边框和内边距不得把 760px 映射成窄栏。 */
test("Review resize 按 content box 保持严格 760px 宽栏断点", () => {
  const contentWidth = reviewContentBoxWidth({
    outerWidth: 765,
    borderLeftWidth: 1,
    borderRightWidth: 1,
    paddingLeft: 1,
    paddingRight: 2,
  });
  assert.equal(contentWidth, 760);
  assert.equal(reviewLayoutForContentWidth(759.999), "narrow");
  assert.equal(reviewLayoutForContentWidth(contentWidth), "wide");
  assert.throws(
    () =>
      reviewContentBoxWidth({
        outerWidth: 3,
        borderLeftWidth: 1,
        borderRightWidth: 1,
        paddingLeft: 1,
        paddingRight: 1,
      }),
    /box metrics are invalid/u,
  );
});

/** AI finding 面板另行覆盖窄容器下三种主题与三种 CDP 密度的完整交叉。 */
test("CODING_REVIEW_AI_NARROW_MATRIX 覆盖 9 帧主题与 DPI 矩阵", () => {
  assert.equal(CODING_REVIEW_AI_NARROW_MATRIX.length, 9);
  assert.ok(CODING_REVIEW_AI_NARROW_MATRIX.every(({ width }) => width === 360));
  assert.deepEqual(
    new Set(CODING_REVIEW_AI_NARROW_MATRIX.map(({ theme }) => theme)),
    new Set(["light", "dark", "system"]),
  );
  assert.deepEqual(
    new Set(CODING_REVIEW_AI_NARROW_MATRIX.map(({ devicePixelRatio }) => devicePixelRatio)),
    new Set([1, 1.25, 1.5]),
  );
  assert.deepEqual(
    new Set(
      CODING_REVIEW_AI_NARROW_MATRIX.map(
        ({ theme, devicePixelRatio }) => `${theme}/${devicePixelRatio}`,
      ),
    ),
    new Set([
      "light/1",
      "light/1.25",
      "light/1.5",
      "dark/1",
      "dark/1.25",
      "dark/1.5",
      "system/1",
      "system/1.25",
      "system/1.5",
    ]),
  );
  assert.equal(
    CODING_REVIEW_AI_NARROW_MATRIX.find(({ theme }) => theme === "system")?.systemColorScheme,
    "dark",
  );
});

/** finding 截图必须覆盖全部 palette 与主题组合，并在宽窄容器各留一帧。 */
test("CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX 覆盖 30 帧主题、palette 与布局矩阵", () => {
  assert.equal(CODING_REVIEW_AI_PALETTES.length, 5);
  assert.equal(CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX.length, 30);
  const themes = ["light", "dark", "system"];
  const layouts = [
    { width: 760, layout: "wide" },
    { width: 360, layout: "narrow" },
  ];
  for (const theme of themes) {
    for (const { value: palette, label: paletteLabel } of CODING_REVIEW_AI_PALETTES) {
      const frames = CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX.filter(
        (frame) => frame.theme === theme && frame.palette === palette,
      );
      assert.deepEqual(
        frames.map(({ width, layout }) => ({ width, layout })),
        layouts,
        `${theme}/${palette} 必须覆盖宽窄两种容器`,
      );
      assert.ok(frames.every((frame) => frame.paletteLabel === paletteLabel));
      assert.ok(
        frames.every(
          (frame) =>
            frame.systemColorScheme === (theme === "system" ? "dark" : theme) &&
            frame.reducedMotion === false,
        ),
      );
    }
  }
});

/** 每帧截图的 Native Diff 身份必须与当前未提交快照及已呈现正文完全一致。 */
test("AI Review matrix 只接受已解析且当前可见的匹配 Diff", () => {
  const revision = "a".repeat(64);
  const fileId = "b".repeat(64);
  const evidence = {
    state: "ready",
    scopeLabel: "审阅范围：未提交",
    source: { kind: "uncommitted" },
    workspaceId: "ws_0123456789abcdef",
    snapshotRevision: revision,
    fileId,
    layer: "untracked",
    revision,
    resultFileId: fileId,
    resultLayer: "untracked",
    resultRevision: revision,
    callPhase: "resolved",
    loading: false,
    visibleErrorCount: 0,
    diffHeaderVisible: true,
  };
  assert.deepEqual(validateCodingReviewDiffReadiness(evidence), {
    source: { kind: "uncommitted" },
    workspaceId: "ws_0123456789abcdef",
    snapshotRevision: revision,
    fileId,
    layer: "untracked",
    revision,
    status: "ready",
  });

  for (const invalid of [
    { ...evidence, source: { kind: "branch", refId: "main" } },
    { ...evidence, resultFileId: "c".repeat(64) },
    { ...evidence, resultLayer: undefined },
    { ...evidence, resultLayer: "comparison" },
    { ...evidence, resultRevision: "d".repeat(64) },
    { ...evidence, revision: "e".repeat(64), resultRevision: "e".repeat(64) },
    { ...evidence, loading: true },
    { ...evidence, visibleErrorCount: 1 },
    { ...evidence, diffHeaderVisible: false },
    { ...evidence, callPhase: "started" },
  ]) {
    assert.throws(() => validateCodingReviewDiffReadiness(invalid));
  }
});

/** Browser predicate follows JA-RPC's resultLayer field and rejects stale snapshot revisions. */
test("AI Review readiness matches resultLayer rather than absent input layer", () => {
  const ready = JSON.parse(JSON.stringify(readDiffReadinessForTest({})));
  assert.equal(ready.state, "ready");
  assert.equal(ready.layer, "untracked");
  assert.equal(ready.callPhase, "resolved");
  validateCodingReviewDiffReadiness(ready);

  for (const invalid of [
    { resultLayer: undefined },
    { resultLayer: "comparison" },
    { revision: "c".repeat(64) },
    { resultRevision: "d".repeat(64) },
  ]) {
    assert.equal(readDiffReadinessForTest(invalid), false);
  }
});

/** Comparison Diff readiness reuses the same identity gate and binds its response layer to the requested snapshot. */
test("comparison Review readiness rejects wrong layer, revision, file, source, or workspace", () => {
  const source = { kind: "branch", refId: "local" };
  const expected = { scopeLabel: "审阅范围：local", source };
  const ready = JSON.parse(JSON.stringify(readDiffReadinessForTest({}, source)));
  assert.equal(ready.state, "ready");
  assert.equal(ready.layer, "comparison");
  assert.equal(ready.resultLayer, "comparison");
  assert.equal(ready.fileId, ready.resultFileId);
  assert.equal(ready.snapshotRevision, ready.resultRevision);
  assert.deepEqual(ready.source, source);
  validateCodingReviewDiffReadiness(ready, expected);

  const invalidCases = [
    { name: "missing result layer", override: { resultLayer: undefined } },
    { name: "wrong result layer", override: { resultLayer: "untracked" } },
    { name: "old snapshot revision", override: { resultRevision: "c".repeat(64) } },
    { name: "wrong request revision", override: { revision: "c".repeat(64) } },
    { name: "wrong file", override: { fileId: "d".repeat(64) } },
    { name: "wrong source", override: { source: { kind: "branch", refId: "other" } } },
    { name: "wrong workspace", override: { workspaceId: "ws_fedcba9876543210" } },
  ];
  for (const invalid of invalidCases) {
    assert.equal(readDiffReadinessForTest(invalid.override, source), false, invalid.name);
  }
});

/** Git-only 即使被篡改为 PASS 也必须由报告验证器拒绝。 */
test("validateReviewRedesignReport 拒绝 Git-only 冒绿", () => {
  const report = validReport("git");
  report.verdict = "PASS";
  assert.throws(() => validateReviewRedesignReport(report, { requestedScope: "git" }), /Git-only/u);
});

/** Full 报告没有已通过的 Turn lifecycle 时不能通过。 */
test("validateReviewRedesignReport 拒绝缺少 Turn lifecycle 的 full 报告", () => {
  const report = validReport("full");
  report.turnReview = { status: "observed" };
  assert.throws(
    () => validateReviewRedesignReport(report, { requestedScope: "full" }),
    /Turn lifecycle/u,
  );
});

/** 报告必须同时证明 RPC ready 与 UI 投影，不能把已渲染的 Shell 当作连接成功。 */
test("validateReviewRedesignReport 要求真实 runtime 与状态投影证据", () => {
  const report = validReport();
  report.runtimeReadiness.status = "starting";
  assert.throws(
    () => validateReviewRedesignReport(report, { requestedScope: "git" }),
    (error) =>
      error.code === "ERR_ASSERTION" && error.actual === "starting" && error.expected === "ready",
  );
});

/** 报告可吸收 CDP 的有限浮点回读噪声，但不能把其他缩放比例舍入成受支持值。 */
test("validateReviewRedesignReport 仅容忍受支持 DPI 的 CDP 浮点噪声", () => {
  const report = validReport();
  report.gitReview.matrix[0].devicePixelRatio = 1.0000000298023224;
  report.gitReview.matrix[1].devicePixelRatio = 1.2499999701976776;
  assert.equal(validateReviewRedesignReport(report, { requestedScope: "git" }), report);

  const unsupported = validReport();
  unsupported.gitReview.matrix[0].devicePixelRatio = 1.00001;
  assert.throws(
    () => validateReviewRedesignReport(unsupported, { requestedScope: "git" }),
    /unsupported/u,
  );
  assert.equal(normalizeDevicePixelRatio(1.5), 1.5);
});

/** 真窗报告必须记录可对照 760px content-box 的尺寸，不能只用外框宽度推断布局。 */
test("validateReviewRedesignReport 拒绝不匹配的 Review content-box 证据", () => {
  const report = validReport();
  report.gitReview.matrix[2].actualContentWidth = 758;
  report.gitReview.matrix[2].layout = "narrow";
  assert.throws(
    () => validateReviewRedesignReport(report, { requestedScope: "git" }),
    /content-box breakpoint/u,
  );
});

/** 同时具备 Git 与 Turn 闭环证据的 full 报告才可 PASS。 */
test("validateReviewRedesignReport 接受完整闭环报告", () => {
  const report = validReport("full");
  assert.equal(validateReviewRedesignReport(report, { requestedScope: "full" }), report);
});
