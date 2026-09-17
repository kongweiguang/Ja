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
  "Run frontend type, lint, test, and build gates",
  "Run repository architecture, attribution, and unused-code gates",
  "Run the Rust workspace gates",
  "Run PowerShell script tests",
];

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

/** Confirm bounded fail-fast gates and preserve the Rust workspace/all-targets coverage contract. */
function assertAllSuccessGatesUseNativeFailFast() {
  const source = readFileSync(WORKFLOW_PATH, "utf8");
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
 * 将不依赖 JVM、pnpm 或 Rust 的协议黄金测试固定在耗时验证之前，避免简单合同漂移被整条
 * 串行 CI 掩盖到最后才报告。
 */
function assertProtocolPreflightRunsBeforeHeavyVerification() {
  const source = readFileSync(WORKFLOW_PATH, "utf8");
  const python = source.indexOf("      - name: Run Python script tests");
  const jvm = source.indexOf("      - name: Run JVM verification and build the Rust test fixture");
  const frontend = source.indexOf("      - name: Run frontend type, lint, test, and build gates");
  const rust = source.indexOf("      - name: Run the Rust workspace gates");
  assert.notEqual(python, -1, "Python protocol preflight is missing");
  assert.notEqual(jvm, -1, "JVM verification is missing");
  assert.notEqual(frontend, -1, "frontend verification is missing");
  assert.notEqual(rust, -1, "Rust verification is missing");
  assert.ok(python < jvm, "Python protocol preflight must run before JVM verification");
  assert.ok(python < frontend, "Python protocol preflight must run before frontend verification");
  assert.ok(python < rust, "Python protocol preflight must run before Rust verification");
}

test(
  "protocol golden preflight runs before heavyweight verification",
  assertProtocolPreflightRunsBeforeHeavyVerification,
);

/**
 * 发布只可复用同一 SHA 的完整 main CI 结论；调用方仍把签名、原生矩阵与 Draft 交给原有
 * 工作流，避免复制产物链后在两处维护不同的安全策略。
 */
function assertReleaseReusesOnlyVerifiedMainCandidates() {
  const nativeWorkflow = readFileSync(WORKFLOW_PATH, "utf8");
  const releaseWorkflow = readFileSync(RELEASE_WORKFLOW_PATH, "utf8");
  assert.match(nativeWorkflow, /workflow_call:\s+inputs:[\s\S]*?skip_verification:/u);
  assert.match(nativeWorkflow, /JA_SOURCE_COMMIT: \$\{\{ inputs\.source_commit \|\| github\.sha \}\}/u);
  assert.match(releaseWorkflow, /git fetch origin main --depth=1/u);
  assert.match(releaseWorkflow, /actions\/workflows\/native-app-server\.yml\/runs\?event=push/u);
  assert.match(releaseWorkflow, /select\(\.conclusion == "success"\)/u);
  assert.match(releaseWorkflow, /uses: \.\/\.github\/workflows\/native-app-server\.yml/u);
  assert.match(releaseWorkflow, /release: true/u);
  assert.match(releaseWorkflow, /skip_verification: true/u);
}

test(
  "release only signs a successful current main candidate",
  assertReleaseReusesOnlyVerifiedMainCandidates,
);
