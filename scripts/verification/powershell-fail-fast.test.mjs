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
  "Run release workflow contract tests serially",
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
 * 不解析任意 YAML，直接提取一个 workflow 步骤，避免回归测试引入额外依赖。
 * 测试只检查该步骤的限定内容，因此不会误把写在其他 job 或全局默认值中的设置判为有效。
 */
function workflowStepBody(source, name) {
  const marker = `      - name: ${name}`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `workflow step is missing: ${name}`);
  const next = source.indexOf("\n      - name:", start + marker.length);
  return source.slice(start, next === -1 ? source.length : next);
}

/**
 * 使用真实的原生命令退出码 7，验证 CI 使用的 PowerShell 偏好设置。
 * 后续成功标记不得出现，以证明门禁不会在中间原生命令失败后仍报告通过。
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

/** 独立验证并行启动；每个 PowerShell gate 仍失败即停，公开发布继续要求同 SHA 的整体 CI 成功。 */
function assertIndependentVerificationRunsInParallel() {
  const source = readWorkflowText(WORKFLOW_PATH);
  const fastChecksJob = source.indexOf("  fast-checks:\n");
  const frontendJob = source.indexOf("  frontend-verification:\n");
  const verificationJob = source.indexOf("  fast-verification:\n");
  const nativeJob = source.indexOf("  native-app-server:\n");
  const npmJob = source.indexOf("  npm-package-verification:\n");
  assert.ok(fastChecksJob >= 0 && frontendJob > fastChecksJob && verificationJob > frontendJob);
  assert.ok(nativeJob > verificationJob && npmJob > nativeJob);
  for (const [start, end] of [
    [frontendJob, verificationJob],
    [nativeJob, npmJob],
  ]) {
    const job = source.slice(start, end);
    assert.doesNotMatch(job, /needs:/u);
    assert.doesNotMatch(job, /needs\.(?:fast-checks|frontend-verification|fast-verification)/u);
  }
  for (const name of [
    "Run Python script tests",
    "Run JVM verification and build the Rust test fixture",
    "Run frontend tests and Vite build",
    "Run the Rust workspace gates",
  ]) {
    assert.ok(source.includes(`      - name: ${name}`), `${name} is missing`);
  }
  const verification = source.slice(verificationJob, nativeJob);
  assert.match(verification, /needs: change-scope/u);
  assert.match(
    verification,
    /always\(\)[\s\S]*needs\.change-scope\.outputs\.run_full_verification != 'false'/u,
  );
  const release = readWorkflowText(RELEASE_WORKFLOW_PATH);
  assert.match(release, /\.head_sha == \$sha[\s\S]*\.conclusion == "success"/u);
}

test(
  "independent verification runs in parallel before verified publication",
  assertIndependentVerificationRunsInParallel,
);

/** 将 Provider fuzz 门禁锁定到 Core 模块的现存测试，避免重命名后 CI 静默跳过。 */
function assertProviderFuzzStepsTargetCurrentCoreTests() {
  const source = readWorkflowText(WORKFLOW_PATH);
  const retryAfter = workflowStepBody(source, "Fuzz Provider Retry-After parsing");
  const eventJson = workflowStepBody(source, "Fuzz Provider event JSON parsing");
  assert.match(retryAfter, /-pl :ja-agent-core -am/u);
  assert.match(retryAfter, /ProviderInputFuzzTest#retryAfterAlwaysStaysWithinTransportBudget/u);
  assert.match(eventJson, /-pl :ja-agent-core -am/u);
  assert.match(eventJson, /ProviderInputFuzzTest#providerJsonFailsClosedForArbitraryBytes/u);
  assert.doesNotMatch(source, /strictSseReaderFailsClosedForArbitraryBytes/u);
}

test(
  "Provider fuzz steps select current Core tests",
  assertProviderFuzzStepsTargetCurrentCoreTests,
);

/** 发布只能复用同一 SHA 的成功 main CI 精简 artifact，并保留签名矩阵的真实后续门禁。 */
function assertReleaseReusesOnlyVerifiedMainCandidates() {
  const nativeWorkflow = readWorkflowText(WORKFLOW_PATH);
  const releaseWorkflow = readWorkflowText(RELEASE_WORKFLOW_PATH);
  assert.match(nativeWorkflow, /workflow_call:\s+inputs:[\s\S]*?skip_verification:/u);
  assert.match(nativeWorkflow, /verified_run_id:[\s\S]*?required: true[\s\S]*?type: string/u);
  assert.match(
    nativeWorkflow,
    /JA_SOURCE_COMMIT: \$\{\{ inputs\.source_commit \|\| github\.sha \}\}/u,
  );
  assert.match(
    nativeWorkflow,
    /actions\/download-artifact@[\da-f]+[\s\S]*?run-id: \$\{\{ inputs\.verified_run_id \}\}/u,
  );
  assert.match(nativeWorkflow, /github-token: \$\{\{ github\.token \}\}/u);
  assert.match(nativeWorkflow, /restore-verified-native\.py[\s\S]*?--verified-run-id/u);
  assert.match(
    nativeWorkflow,
    /name: ja-native-reuse-\$\{\{ matrix\.platform \}\}-\$\{\{ matrix\.arch \}\}/u,
  );
  assert.match(
    nativeWorkflow,
    /Build App Server Native Image \(Windows\)[\s\S]*?env\.JA_RELEASE != 'true'/u,
  );
  assert.match(
    nativeWorkflow,
    /Build App Server Native Image \(macOS\)[\s\S]*?env\.JA_RELEASE != 'true'/u,
  );
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
  assert.match(
    releaseWorkflow,
    /verified_run_id: \$\{\{ needs\.verify-release-candidate\.outputs\.verified_run_id \}\}/u,
  );
}

test(
  "release only signs a successful current main candidate",
  assertReleaseReusesOnlyVerifiedMainCandidates,
);
