// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  parseArguments,
  percentile,
  readBaselineReport,
  summarize,
  validateMavenToolchain,
  validateWorkspaceSwitchReport,
} from "./workspace-switch-webview2.mjs";

/** 构造 after 阶段的最小完整报告，单测只覆盖报告合同，不伪造真窗运行结果。 */
function validReport(phase = "after") {
  return {
    schemaVersion: 1,
    phase,
    runtime: {
      platform: "win32",
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
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
      spinnerInRecentHeading: true,
      hiddenReviewSnapshotDelta: 0,
      pageErrors: 0,
    },
    baseline: { available: false, absoluteOnly: true },
  };
}

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

/** after 报告必须同时证明历史非空、滚动复原、标题行 spinner 与隐藏 Review 零 snapshot。 */
test("validateWorkspaceSwitchReport 接受 after 闭环", () => {
  const report = validReport();
  assert.equal(validateWorkspaceSwitchReport(report), report);
});

/** 旧 loading 占位或隐藏 Review snapshot 任一出现都必须关闭 after 门禁。 */
test("validateWorkspaceSwitchReport 拒绝 loading 与隐藏 IO 退化", () => {
  const legacy = validReport();
  legacy.correctness.spinnerLegacyPlaceholder = true;
  assert.throws(() => validateWorkspaceSwitchReport(legacy));
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

/** baseline 允许记录旧实现缺少的新 UI 事实，但仍保留真实压力和隐藏 Review 约束。 */
test("validateWorkspaceSwitchReport 允许 baseline 只记录绝对值", () => {
  const report = validReport("baseline");
  report.correctness.spinnerLegacyPlaceholder = true;
  report.correctness.spinnerInRecentHeading = false;
  report.correctness.explicitThreadIdentity = false;
  report.correctness.scrollRestored = false;
  report.baseline = { available: false, absoluteOnly: true };
  assert.equal(validateWorkspaceSwitchReport(report), report);
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
