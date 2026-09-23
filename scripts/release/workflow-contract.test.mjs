// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import test from "node:test";

const NATIVE_WORKFLOW_PATH = resolve(".github/workflows/native-app-server.yml");
const RELEASE_WORKFLOW_PATH = resolve(".github/workflows/release.yml");

/** 统一 CRLF/LF 输入，避免 Windows checkout 与 Ubuntu runner 对同一结构产生不同断言结果。 */
function normalizeWorkflow(source) {
  return source.replace(/\r\n/gu, "\n");
}

/** 读取 workflow 时统一 CRLF/LF，静态定位逻辑只处理一个确定的换行格式。 */
function readWorkflow(path) {
  return normalizeWorkflow(readFileSync(path, "utf8"));
}

/** 取得一个 job 的有界文本，避免静态断言误把同名步骤放进其他 job。 */
function jobBody(source, name) {
  const marker = `  ${name}:\n`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `workflow job is missing: ${name}`);
  const jobMarker = /^ {2}[A-Za-z0-9_-]+:\n/gmu;
  jobMarker.lastIndex = start + marker.length;
  const next = jobMarker.exec(source)?.index ?? -1;
  return source.slice(start, next === -1 ? source.length : next);
}

/** 取得一个步骤的有界文本，保持回归测试不依赖额外 YAML 解析器。 */
function stepBody(source, name) {
  const marker = `      - name: ${name}`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `workflow step is missing: ${name}`);
  const stepMarker = /^ {6}- name: /gmu;
  stepMarker.lastIndex = start + marker.length;
  const nextStep = stepMarker.exec(source)?.index ?? -1;
  const jobMarker = /^ {2}[A-Za-z0-9_-]+:\n/gmu;
  jobMarker.lastIndex = start + marker.length;
  const nextJob = jobMarker.exec(source)?.index ?? -1;
  const candidates = [nextStep, nextJob].filter((index) => index >= 0);
  const end = candidates.length === 0 ? source.length : Math.min(...candidates);
  return source.slice(start, end);
}

/** 从 release 的真实 run 块提取脚本，并只替换 GitHub 表达式以便进行语法检查。 */
function releaseBashRun(source, name) {
  const body = stepBody(source, name);
  const runMarker = "        run: |\n";
  const start = body.indexOf(runMarker);
  assert.notEqual(start, -1, `bash run block is missing: ${name}`);
  const script = body.slice(start + runMarker.length);
  return script.replace(/\$\{\{[\s\S]*?\}\}/gu, "GITHUB_EXPRESSION");
}

/** 让 Git Bash 只做 -n 语法检查，不执行预检中的 API、版本或发布命令。 */
function assertReleasePreflightBashSyntax() {
  const source = readWorkflow(RELEASE_WORKFLOW_PATH);
  const script = releaseBashRun(source, "Require current main, verified CI artifacts, and release metadata");
  const bash = process.platform === "win32" ? "C:\\Program Files\\Git\\bin\\bash.exe" : "bash";
  if (process.platform === "win32") assert.ok(existsSync(bash), `Git Bash is missing: ${bash}`);
  const result = spawnSync(bash, ["--noprofile", "--norc", "-n"], {
    input: script,
    encoding: "utf8",
    timeout: 10_000,
    windowsHide: true,
  });
  assert.equal(result.error, undefined, result.error?.message);
  assert.equal(result.status, 0, `${result.stdout ?? ""}\n${result.stderr ?? ""}`);
}

test("release preflight Bash is syntactically valid", assertReleasePreflightBashSyntax);

/** 回归验证同一 workflow 在 Windows CRLF 与仓库 LF checkout 下都能被正确定位。 */
function assertWorkflowLineEndingNormalization() {
  const lf = readFileSync(RELEASE_WORKFLOW_PATH, "utf8").replace(/\r\n/gu, "\n");
  const crlf = lf.replace(/\n/gu, "\r\n");
  assert.equal(normalizeWorkflow(lf), normalizeWorkflow(crlf));
  assert.match(jobBody(normalizeWorkflow(crlf), "verify-release-candidate"), /Require current main/u);
}

test("workflow contract parser normalizes LF and CRLF", assertWorkflowLineEndingNormalization);

/** 固定快速门禁与两个并行重门禁的依赖图，防止拆分后 native 提前启动。 */
function assertNativeVerificationGraph() {
  const source = readWorkflow(NATIVE_WORKFLOW_PATH);
  const fastChecks = jobBody(source, "fast-checks");
  const frontend = jobBody(source, "frontend-verification");
  const verification = jobBody(source, "fast-verification");
  const native = jobBody(source, "native-app-server");
  assert.match(fastChecks, /Run Python script tests[\s\S]*test_restore_verified_native/u);
  assert.match(fastChecks, /Run Node script tests[\s\S]*create-updater-manifest\.test\.mjs[\s\S]*workflow-contract\.test\.mjs/u);
  assert.match(fastChecks, /pnpm typecheck[\s\S]*pnpm lint[\s\S]*pnpm check:architecture[\s\S]*pnpm check:unused/u);
  assert.match(fastChecks, /cargo fmt --all -- --check/u);
  assert.match(frontend, /needs: fast-checks/u);
  assert.match(frontend, /vitest run --maxWorkers=1 --minWorkers=1/u);
  assert.match(frontend, /vite build --config apps\/desktop\/vite\.config\.ts/u);
  assert.doesNotMatch(frontend, /^\s+pnpm build\s*$/mu);
  assert.match(verification, /needs: fast-checks/u);
  assert.match(verification, /tests\/contract\/run\.ps1/u);
  assert.match(verification, /Run JVM verification and build the Rust test fixture/u);
  assert.match(verification, /Run the Rust workspace gates/u);
  assert.match(native, /needs: \[fast-checks, frontend-verification, fast-verification\]/u);
  assert.match(native, /needs\.frontend-verification\.result == 'success'/u);
  assert.match(native, /needs\.fast-verification\.result == 'success'/u);
  assert.match(native, /!cancelled\(\)/u);
}

test("native matrix waits for both verification jobs", assertNativeVerificationGraph);

/** 固定成功主 CI 的精简 artifact、恢复 CLI 与仅跳过 Native Image 编译的边界。 */
function assertNativeReuseContract() {
  const source = readWorkflow(NATIVE_WORKFLOW_PATH);
  assert.match(source, /verified_run_id:[\s\S]*required: true[\s\S]*type: string/u);
  assert.match(source, /name: ja-native-reuse-\$\{\{ matrix\.platform \}\}-\$\{\{ matrix\.arch \}\}/u);
  assert.match(source, /app-server\/target\/ja-app-server\.exe/u);
  const windowsReuse = stepBody(source, "Prepare native reuse artifact (Windows)");
  const macReuse = stepBody(source, "Prepare native reuse artifact (macOS)");
  for (const reuseStep of [windowsReuse, macReuse]) {
    assert.match(reuseStep, /native-reuse/u);
    assert.match(reuseStep, /build-report\.json/u);
    assert.match(reuseStep, /ja-app-server\.json/u);
  }
  assert.match(source, /run-id: \$\{\{ inputs\.verified_run_id \}\}/u);
  assert.match(source, /github-token: \$\{\{ github\.token \}\}/u);
  assert.match(source, /restore-verified-native\.py[\s\S]*--source-commit[\s\S]*--verified-run-id/u);
  assert.match(source, /Build App Server Native Image \(Windows\)[\s\S]*if: matrix\.platform == 'windows' && env\.JA_RELEASE != 'true'/u);
  assert.match(source, /Build App Server Native Image \(macOS\)[\s\S]*if: matrix\.platform == 'macos' && env\.JA_RELEASE != 'true'/u);
  assert.match(source, /Upload native build evidence[\s\S]*name: ja-native-app-server-/u);
}

test("release reuses only the verified native executable", assertNativeReuseContract);

/** 固定 release preflight 的权限、exact SHA、三 artifact 和发布冲突门禁。 */
function assertReleasePreflightContract() {
  const source = readWorkflow(RELEASE_WORKFLOW_PATH);
  assert.match(source, /permissions:[\s\S]*contents: read[\s\S]*actions: read/u);
  assert.match(source, /head_branch == "main"/u);
  assert.match(source, /\.head_sha == \$sha[\s\S]*\.conclusion == "success"/u);
  assert.match(source, /ja-native-reuse-\$target/u);
  assert.match(source, /\.expired == false[\s\S]*\.size_in_bytes/u);
  assert.match(source, /release notes are missing or empty/u);
  assert.match(source, /pnpm version:check/u);
  assert.match(source, /TAURI_SIGNING_PRIVATE_KEY is missing/u);
  assert.match(source, /releases\?per_page=100/u);
  assert.match(source, /git ls-remote --refs origin/u);
  assert.match(source, /echo "verified_run_id=\$verified_run_id"/u);
  assert.match(source, /verified_run_id: \$\{\{ needs\.verify-release-candidate\.outputs\.verified_run_id \}\}/u);
  assert.match(source, /cancel-in-progress: false/u);
  assert.match(source, /signed-native-matrix:[\s\S]*actions: read/u);
  const nativeWorkflow = readWorkflow(NATIVE_WORKFLOW_PATH);
  assert.match(nativeWorkflow, /updater-draft-release:[\s\S]*if: \$\{\{ !cancelled\(\)/u);
}

test("release preflight publishes an exact verified run id", assertReleasePreflightContract);
