// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import {
  REVIEW_REDESIGN_MATRIX,
  validateReviewRedesignReport,
} from "./review-redesign-webview2-driver.mjs";
import {
  assertOwnedTemporaryPath,
  buildLaunchEnvironment,
  createReviewGitFixture,
  parseArguments,
} from "./review-redesign-production.mjs";

const execFileAsync = promisify(execFile);

/** 构造满足 Git 证据闭集的最小报告，测试只按覆盖点覆盖字段。 */
function validReport(scope = "git") {
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    gitAdapter: "real_native",
    scope,
    verdict: scope === "full" ? "PASS" : "NOT VERIFIED",
    gitReview: {
      status: "passed",
      hiddenSnapshotDelta: 0,
      matrix: REVIEW_REDESIGN_MATRIX.map((frame) => ({
        requestedWidth: frame.width,
        themeMode: frame.theme,
        devicePixelRatio: frame.devicePixelRatio,
        reducedMotion: frame.reducedMotion,
        dpiEvidence: "cdp_emulated",
      })),
    },
    turnReview: scope === "full" ? { status: "passed" } : { status: "not_verified" },
  };
}

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

/** 同时具备 Git 与 Turn 闭环证据的 full 报告才可 PASS。 */
test("validateReviewRedesignReport 接受完整闭环报告", () => {
  const report = validReport("full");
  assert.equal(validateReviewRedesignReport(report, { requestedScope: "full" }), report);
});
