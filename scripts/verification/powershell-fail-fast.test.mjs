// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const WORKFLOW_PATH = resolve(".github/workflows/native-app-server.yml");
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
