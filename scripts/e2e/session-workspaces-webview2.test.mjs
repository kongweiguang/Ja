// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import {
  parseArguments,
  terminalOutputDiagnostics,
  terminalTextContainsWorkspaceRoot,
  validateSessionWorkspacesReport,
} from "./session-workspaces-webview2.mjs";

/** 构造完整会话隔离报告，使每个反例只破坏一项运行时不变量。 */
function validReport() {
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    fixture: {
      transport: "typed_tauri_session_adapter",
      sessionThreadCount: 2,
      distinctWorkspaceIds: true,
      cwdOmittedForCreate: true,
      providerTurnInvokes: 0,
    },
    workspaces: {
      distinctCanonicalRoots: true,
      fileMarkersScoped: true,
      terminalRootsScoped: true,
      terminalWorkspaceBindingMatches: true,
      terminalFingerprintsDistinct: true,
      terminalSurvivesSessionSwitch: true,
      switchClosedTerminals: 0,
      folderActionDidNotSelectItsThread: true,
      folderActionWorkspaceMatches: true,
      legacyActionHiddenForFreshSession: true,
    },
    sideTask: { created: true, inheritedParentWorkspace: true },
    restart: {
      kind: "runtime_generation",
      fromGeneration: 2,
      toGeneration: 3,
      advanced: true,
      ready: true,
      sessionDirectoriesRestored: true,
      terminalIdentityRestored: true,
    },
    project: {
      selected: true,
      workspaceKind: "project",
      threadWorkspaceKind: "project",
      identityMatches: true,
    },
    pageErrors: [],
  };
}

test("参数固定 JDK 25、隔离 Cargo target，并保留失败诊断现场", () => {
  const parsed = parseArguments([
    "--evidence-directory",
    join(tmpdir(), "ja-session-workspaces-evidence"),
    "--jar",
    join(tmpdir(), "ja-app-server.jar"),
    "--preserve-failed-profile",
  ]);
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(parsed.cargoTargetDirectory, /target[\\/]codex-session-workspaces$/u);
  assert.equal(parsed.preserveFailedProfile, true);
});

test("两个会话、重启恢复、侧边任务和项目身份报告通过", () => {
  const report = validReport();
  assert.equal(validateSessionWorkspacesReport(report), report);
});

test("cwd 探针拼合 xterm 视觉折行并忽略提示符和命令回显", () => {
  const root = "C:\\isolated ja\\workspaces\\thr_abc";
  const extendedRoot = `\\\\?\\${root}`;
  const begin = "JA_SESSION_CWD_BEGIN_test123";
  const end = "JA_SESSION_CWD_END_test123";
  const encodedRoot = Buffer.from(root, "utf8").toString("base64");
  const output = [
    `PS ${root}> Write-Output '${begin}'; [Convert]::ToBase64String(...); Write-Output '${end}'`,
    begin.slice(0, 20),
    begin.slice(20),
    encodedRoot.slice(0, 16),
    encodedRoot.slice(16),
    end.slice(0, 22),
    end.slice(22),
    `PS ${root}>`,
  ].join("\r\n");
  assert.equal(terminalTextContainsWorkspaceRoot(output, extendedRoot, begin, end), true);
  assert.equal(
    terminalTextContainsWorkspaceRoot(`PS ${root}> Write-Output cwd\r\n${root}`, root, begin, end),
    false,
  );

  const diagnostics = terminalOutputDiagnostics(
    output,
    root,
    "C:\\isolated ja",
    "C:\\isolated project",
    true,
    begin,
    end,
  );
  assert.equal(diagnostics.probeCommandWasExecuted, true);
  assert.equal(diagnostics.queryOutputMatchesExpectedRoot, true);
  assert.equal(diagnostics.cwdValueOwnership.insideIsolatedJaHome, true);
  assert.equal(diagnostics.cwdValueOwnership.insideExpectedSessionRoot, true);
  assert.equal(JSON.stringify(diagnostics).includes(root), false);
});

test("报告拒绝跨会话终端关闭、无代际重启或错误项目身份", () => {
  const closed = validReport();
  closed.workspaces.switchClosedTerminals = 1;
  assert.throws(() => validateSessionWorkspacesReport(closed));

  const restart = validReport();
  restart.restart.toGeneration = restart.restart.fromGeneration;
  assert.throws(() => validateSessionWorkspacesReport(restart));

  const project = validReport();
  project.project.threadWorkspaceKind = "session";
  assert.throws(() => validateSessionWorkspacesReport(project));
});
