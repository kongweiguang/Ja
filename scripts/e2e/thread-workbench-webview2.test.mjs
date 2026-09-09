// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { parseArguments, validateThreadWorkbenchReport } from "./thread-workbench-webview2.mjs";

/** 构造完整报告基线，让每个反例只破坏一个 Thread/Workbench 不变量。 */
function validReport() {
  const terminal = {
    visible: true,
    activeTab: "terminal",
    tabs: ["terminal"],
    hasFiles: false,
    hasTerminal: true,
    hasPreview: false,
    terminalSessionCount: 1,
  };
  const preview = {
    visible: true,
    activeTab: "preview",
    tabs: ["preview"],
    hasFiles: false,
    hasTerminal: false,
    hasPreview: true,
    terminalSessionCount: 0,
  };
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    fixture: {
      transport: "typed_tauri_history_adapter",
      durableThreadCount: 2,
      nonEmptyTitles: true,
      providerTurnInvokes: 0,
    },
    binding: {
      switchSequence: ["A", "B", "A", "B", "A", "B"],
      bDefaultEmpty: true,
      aInitial: { ...terminal },
      bInitial: { ...preview },
      aRestored: { ...terminal },
      bRestored: { ...preview },
      aSecondRestore: { ...terminal },
      bSecondRestore: { ...preview },
      isolated: true,
    },
    nativePreview: {
      openResolved: 1,
      hiddenLayoutResolved: 2,
      visibleLayoutResolved: 3,
      targetRestored: true,
    },
    nativeTerminal: {
      aSessionFingerprint: "a".repeat(64),
      bSessionFingerprint: "b".repeat(64),
      distinctSessions: true,
      bCloseResolved: 1,
      aSessionSurvivedBClosure: true,
    },
    pageErrors: [],
  };
}

test("参数固定 JDK 25 默认值、独立 Cargo target 并接受显式 EdgeDriver", () => {
  const edgeDriver = join(tmpdir(), "msedgedriver.exe");
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-thread-workbench-evidence"),
    "--jar",
    join(tmpdir(), "ja-app-server.jar"),
    "--edge-driver",
    edgeDriver,
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-thread-workbench$/u);
  assert.equal(parsed.edgeDriver, edgeDriver);
});

test("完整双会话绑定与 native Preview 报告通过", () => {
  const report = validReport();
  assert.equal(validateThreadWorkbenchReport(report), report);
});

test("B 未经历默认空右栏时报告失败关闭", () => {
  const report = validReport();
  report.binding.bDefaultEmpty = false;
  assert.throws(() => validateThreadWorkbenchReport(report));
});

test("A 混入 B Preview Tab 时报告失败关闭", () => {
  const report = validReport();
  report.binding.aRestored = {
    visible: true,
    activeTab: "preview",
    tabs: ["files", "preview"],
    hasFiles: true,
    hasTerminal: false,
    hasPreview: true,
    terminalSessionCount: 0,
  };
  assert.throws(() => validateThreadWorkbenchReport(report));
});

test("存在 Provider Turn 或缺少 native layout 恢复时报告失败关闭", () => {
  const provider = validReport();
  provider.fixture.providerTurnInvokes = 1;
  assert.throws(() => validateThreadWorkbenchReport(provider));

  const native = validReport();
  native.nativePreview.visibleLayoutResolved = 1;
  assert.throws(() => validateThreadWorkbenchReport(native));
});

test("B 与 A 复用同一 native Terminal session 时报告失败关闭", () => {
  const report = validReport();
  report.nativeTerminal.bSessionFingerprint = report.nativeTerminal.aSessionFingerprint;
  assert.throws(() => validateThreadWorkbenchReport(report));
});
