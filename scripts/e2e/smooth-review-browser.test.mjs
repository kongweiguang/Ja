// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  parseArguments,
  percentile,
  validateSmoothReviewReport,
} from "./smooth-review-browser.mjs";

/** 最小合法报告固定 fake/native 边界和全部丝滑体验硬断言。 */
function validReport() {
  return {
    contractVersion: 1,
    runtime: "production_turn_review_browser_fixture",
    nativeVerified: false,
    nativeArtifactVerified: false,
    metrics: {
      small: { samples: 30, p95Ms: 250 },
      large: { samples: 30, p95Ms: 700 },
      selection: { samples: 60, p95Ms: 40 },
      interactionP95Ms: 50,
      longTaskP95Ms: 30,
    },
    correctness: {
      singleFileRead: true,
      boundedConcurrency: true,
      latestWins: true,
      abaReread: true,
      noCache: true,
      noPrefetch: true,
      plainBeforeTokens: true,
      plainTextVisibleWhileLoading: true,
      tokensReady: true,
      hiddenReadDelta: 0,
      loadingHiddenBefore120Ms: true,
      loadingVisibleAfter120Ms: true,
      workersBalancedAfterHide: true,
      highlightWorkersBalancedAfterHide: true,
      maxActiveReads: 2,
      activeReadsAfterHide: 0,
      consoleErrors: 0,
    },
    screenshots: [{}, {}, {}, {}],
  };
}

/** CLI 不接受隐式目录或额外参数，避免证据散落到未知位置。 */
test("parseArguments 强制唯一证据目录", () => {
  const path = join(tmpdir(), "ja-smooth-review-evidence");
  assert.equal(parseArguments(["--evidence-directory", path]).evidenceDirectory, resolvePath(path));
  assert.throws(() => parseArguments([]), /evidence-directory/u);
  assert.throws(() => parseArguments(["--evidence-directory", path, "--files", "100"]));
});

/** nearest-rank p95 必须保留实际观测的尾部样本。 */
test("percentile 返回实际观测值", () => {
  assert.equal(percentile([8, 2, 6, 4], 0.95), 8);
  assert.equal(percentile([8, 2, 6, 4], 0.5), 4);
});

/** fake 浏览器证据不得伪装为 native artifact 或 Tauri 真窗证据。 */
test("validateSmoothReviewReport 拒绝 native 冒绿", () => {
  const report = validReport();
  report.nativeVerified = true;
  assert.throws(() => validateSmoothReviewReport(report));
});

/** latest-pending、隐藏清理和体验预算任一退化都失败关闭。 */
test("validateSmoothReviewReport 拒绝正确性与性能退化", () => {
  const intermediate = validReport();
  intermediate.correctness.boundedConcurrency = false;
  assert.throws(() => validateSmoothReviewReport(intermediate));
  const hidden = validReport();
  hidden.correctness.hiddenReadDelta = 1;
  assert.throws(() => validateSmoothReviewReport(hidden));
  const slow = validReport();
  slow.metrics.interactionP95Ms = 201;
  assert.throws(() => validateSmoothReviewReport(slow), /interaction/u);
});

/** 完整闭环才接受报告。 */
test("validateSmoothReviewReport 接受丝滑审阅浏览器报告", () => {
  const report = validReport();
  assert.equal(validateSmoothReviewReport(report), report);
});

/** 独立 helper 避免测试依赖当前工作目录的表示形式。 */
function resolvePath(path) {
  return join(path);
}
