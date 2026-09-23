// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const WORKFLOW_PATH = resolve(".github/workflows/native-app-server.yml");
const RELEASE_WORKFLOW_PATH = resolve(".github/workflows/release.yml");
const ALL_SUCCESS_POWERSHELL_STEPS = [
  "Run fast frontend and architecture gates",
  "Run Rust format gate",
  "Run Node script tests",
  "Run the Rust workspace gates",
  "Run PowerShell script tests",
  "Run frontend tests and Vite build",
  "Restore verified native executable (Windows)",
];

/** 统一 Windows CRLF 与仓库 LF，确保静态门禁在两类 checkout 中检查同一 workflow 结构。 */
function readWorkflowText(path) {
  return readFileSync(path, "utf8").replace(/\r\n/gu, "\n");
}

/**
 * Extract one workflow step without parsing arbitrary YAML so the regression stays dependency-free.
 * The test only inspects the bounded step body and therefore cannot accidentally approve a setting
 * placed in an unrelated job or global default.
 */
function workflowStepBody(source, name) {
  const marker = `      - name: ${name}`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `workflow step is missing: ${name}`);
  const next = source.indexOf("\n      - name:", start + marker.length);
  return source.slice(start, next === -1 ? source.length : next);
}

/**
 * Exercise the exact PowerShell preference used by CI with a real native exit 7. A later success
 * marker must not appear, proving the gate cannot report green after an intermediate native error.
 */
function assertNativeFailureStopsImmediately() {
  if (process.platform !== "win32") return;
  const probe = [
    "$ErrorActionPreference = 'Stop'",
    "$PSNativeCommandUseErrorActionPreference = $true",
    "Write-Output 'JA_POWERSHELL_FAILFAST_BEFORE'",
    "cmd.exe /c exit 7",
    "Write-Output 'JA_POWERSHELL_FAILFAST_AFTER'",
  ].join("; ");
  const result = spawnSync("pwsh", ["-NoProfile", "-NonInteractive", "-Command", probe], {
    encoding: "utf8",
    timeout: 10_000,
    windowsHide: true,
  });
  assert.equal(result.error, undefined);
  assert.notEqual(result.status, 0);
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  assert.match(output, /JA_POWERSHELL_FAILFAST_BEFORE/u);
  assert.doesNotMatch(output, /JA_POWERSHELL_FAILFAST_AFTER/u);
}

test(
  "PowerShell native exit codes stop before later commands",
  assertNativeFailureStopsImmediately,
);

/** 确认拆分后的多命令 PowerShell 门禁都显式传播原生命令失败，并保留 Rust 全量覆盖。 */
function assertAllSuccessGatesUseNativeFailFast() {
  const source = readWorkflowText(WORKFLOW_PATH);
  for (const name of ALL_SUCCESS_POWERSHELL_STEPS) {
    const body = workflowStepBody(source, name);
    assert.match(body, /\$ErrorActionPreference = 'Stop'/u);
    assert.match(body, /\$PSNativeCommandUseErrorActionPreference = \$true/u);
  }
  const rustBody = workflowStepBody(source, "Run the Rust workspace gates");
  assert.match(rustBody, /cargo test --workspace --all-targets --locked -- --test-threads=1/u);
}

test(
  "all-success PowerShell gates enable native fail-fast",
  assertAllSuccessGatesUseNativeFailFast,
);

/**
 * 固定快速 job、前端 job 与合同/JVM/Rust job 的依赖顺序，避免脚本错误等到重编译后才暴露。
 */
function assertFastChecksPrecedeHeavyVerification() {
  const source = readWorkflowText(WORKFLOW_PATH);
  const fastChecksJob = source.indexOf("  fast-checks:\n");
  const frontendJob = source.indexOf("  frontend-verification:\n");
  const verificationJob = source.indexOf("  fast-verification:\n");
  const nativeJob = source.indexOf("  native-app-server:\n");
  const python = source.indexOf("      - name: Run Python script tests");
  const jvm = source.indexOf("      - name: Run JVM verification and build the Rust test fixture");
  const frontend = source.indexOf("      - name: Run frontend tests and Vite build");
  const rust = source.indexOf("      - name: Run the Rust workspace gates");
  assert.ok(fastChecksJob >= 0 && frontendJob > fastChecksJob && verificationJob > frontendJob);
  assert.ok(nativeJob > verificationJob);
  assert.notEqual(python, -1, "Python protocol preflight is missing");
  assert.notEqual(jvm, -1, "JVM verification is missing");
  assert.notEqual(frontend, -1, "frontend verification is missing");
  assert.notEqual(rust, -1, "Rust verification is missing");
  assert.ok(python < jvm, "Python protocol preflight must run before JVM verification");
  assert.ok(python < frontend, "Python protocol preflight must run before frontend verification");
  assert.ok(python < rust, "Python protocol preflight must run before Rust verification");
  assert.match(source, /needs: \[fast-checks, frontend-verification, fast-verification\]/u);
  assert.match(
    source,
    /needs\.frontend-verification\.result == 'success'[\s\S]*needs\.fast-verification\.result == 'success'/u,
  );
}

test(
  "fast checks run before heavyweight verification",
  assertFastChecksPrecedeHeavyVerification,
);

/** 发布只能复用同一 SHA 的成功 main CI 精简 artifact，并保留签名矩阵的真实后续门禁。 */
function assertReleaseReusesOnlyVerifiedMainCandidates() {
  const nativeWorkflow = readWorkflowText(WORKFLOW_PATH);
  const releaseWorkflow = readWorkflowText(RELEASE_WORKFLOW_PATH);
  assert.match(nativeWorkflow, /workflow_call:\s+inputs:[\s\S]*?skip_verification:/u);
  assert.match(nativeWorkflow, /verified_run_id:[\s\S]*?required: true[\s\S]*?type: string/u);
  assert.match(nativeWorkflow, /JA_SOURCE_COMMIT: \$\{\{ inputs\.source_commit \|\| github\.sha \}\}/u);
  assert.match(nativeWorkflow, /actions\/download-artifact@[\da-f]+[\s\S]*?run-id: \$\{\{ inputs\.verified_run_id \}\}/u);
  assert.match(nativeWorkflow, /github-token: \$\{\{ github\.token \}\}/u);
  assert.match(nativeWorkflow, /restore-verified-native\.py[\s\S]*?--verified-run-id/u);
  assert.match(nativeWorkflow, /name: ja-native-reuse-\$\{\{ matrix\.platform \}\}-\$\{\{ matrix\.arch \}\}/u);
  assert.match(nativeWorkflow, /Build App Server Native Image \(Windows\)[\s\S]*?env\.JA_RELEASE != 'true'/u);
  assert.match(nativeWorkflow, /Build App Server Native Image \(macOS\)[\s\S]*?env\.JA_RELEASE != 'true'/u);
  assert.match(releaseWorkflow, /git fetch origin main --depth=1/u);
  assert.match(releaseWorkflow, /actions\/workflows\/native-app-server\.yml\/runs\?event=push/u);
  assert.match(releaseWorkflow, /select\(\.event == "push"[\s\S]*?\.conclusion == "success"\)/u);
  assert.match(releaseWorkflow, /verified_run_id:/u);
  assert.match(releaseWorkflow, /\.expired == false[\s\S]*?\.size_in_bytes/u);
  assert.match(releaseWorkflow, /scripts\/release\/notes\/[\s\S]*?-s/u);
  assert.match(releaseWorkflow, /pnpm version:check/u);
  assert.match(releaseWorkflow, /TAURI_SIGNING_PRIVATE_KEY is missing/u);
  assert.match(releaseWorkflow, /releases\?per_page=100/u);
  assert.match(releaseWorkflow, /git ls-remote --refs origin/u);
  assert.match(releaseWorkflow, /uses: \.\/\.github\/workflows\/native-app-server\.yml/u);
  assert.match(releaseWorkflow, /release: true/u);
  assert.match(releaseWorkflow, /skip_verification: true/u);
  assert.match(releaseWorkflow, /verified_run_id: \$\{\{ needs\.verify-release-candidate\.outputs\.verified_run_id \}\}/u);
}

test(
  "release only signs a successful current main candidate",
  assertReleaseReusesOnlyVerifiedMainCandidates,
);
