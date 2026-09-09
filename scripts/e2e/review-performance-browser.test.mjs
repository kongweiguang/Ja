// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";
import {
  parseArguments,
  percentile,
  summarize,
  validateBrowserPerformanceReport,
} from "./review-performance-browser.mjs";

/** 构造最小合法报告，测试只验证报告门禁而不重复启动浏览器。 */
function validReport() {
  return {
    contractVersion: 1,
    runtime: "production_controller_browser_fixture",
    nativeVerified: false,
    nativeGitVerified: false,
    fileCount: 2_500,
    samples: 5,
    metrics: {
      coldReady: { p95Ms: 500 },
      fileSwitch: { p95Ms: 80 },
      fileRevisit: { p95Ms: 80 },
    },
    correctness: {
      allSamplesPassed: true,
      latestWins: true,
      hiddenCatalogDelta: 0,
      hiddenSnapshotDelta: 0,
      hiddenFileDiffDelta: 0,
      hiddenSubscribeDelta: 0,
      subscriptionsBalanced: true,
    },
  };
}

/** 参数必须固定规模边界，避免误把零样本或无界压力测试写成 PASS。 */
test("parseArguments 接受有界性能参数并拒绝缺失证据目录", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    ".tmp/review-performance",
    "--files",
    "5000",
    "--samples",
    "7",
    "--adapter-delay-ms",
    "8",
  ]);
  assert.equal(parsed.fileCount, 5_000);
  assert.equal(parsed.samples, 7);
  assert.equal(parsed.adapterDelayMs, 8);
  assert.throws(() => parseArguments([]), /evidence-directory/u);
  assert.throws(
    () => parseArguments(["--evidence-directory", ".tmp/x", "--files", "10001"]),
    /between 4 and 10000/u,
  );
});

/** 百分位使用 nearest rank，少量样本不会报告未观测的插值数字。 */
test("percentile 与 summarize 保留真实样本边界", () => {
  assert.equal(percentile([9, 1, 5, 3, 7], 0.5), 5);
  assert.equal(percentile([9, 1, 5, 3, 7], 0.95), 9);
  assert.deepEqual(summarize([9, 1, 5, 3, 7]), {
    count: 5,
    minMs: 1,
    p50Ms: 5,
    p95Ms: 9,
    maxMs: 9,
  });
});

/** fake adapter 报告必须明确拒绝 native 身份冒绿。 */
test("validateBrowserPerformanceReport 拒绝 native 冒绿", () => {
  const report = validReport();
  report.nativeVerified = true;
  assert.throws(() => validateBrowserPerformanceReport(report));
});

/** 隐藏态任何 catalog/snapshot/fileDiff 增量都必须失败关闭。 */
test("validateBrowserPerformanceReport 拒绝隐藏 Review IO", () => {
  const report = validReport();
  report.correctness.hiddenSnapshotDelta = 1;
  assert.throws(() => validateBrowserPerformanceReport(report));
});

/** 三项端到端预算同时满足时才接受 production controller 浏览器报告。 */
test("validateBrowserPerformanceReport 接受完整浏览器性能报告", () => {
  const report = validReport();
  assert.equal(validateBrowserPerformanceReport(report), report);
});
