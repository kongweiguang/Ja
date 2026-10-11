// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import test from "node:test";

const NATIVE_WORKFLOW_PATH = resolve(".github/workflows/native-app-server.yml");
const RELEASE_WORKFLOW_PATH = resolve(".github/workflows/release.yml");
const NPM_WORKFLOW_PATH = resolve(".github/workflows/npm-cli-publish.yml");

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

/** 对预检和最终发布的真实 Bash 块只做语法检查，避免校验时产生外部发布。 */
function assertReleaseBashSyntax() {
  const source = readWorkflow(RELEASE_WORKFLOW_PATH);
  const bash = process.platform === "win32" ? "C:\\Program Files\\Git\\bin\\bash.exe" : "bash";
  if (process.platform === "win32") assert.ok(existsSync(bash), `Git Bash is missing: ${bash}`);
  for (const name of [
    "Require current main, verified CI artifacts, and release metadata",
    "Verify and publish the matching GitHub draft",
  ]) {
    const result = spawnSync(bash, ["--noprofile", "--norc", "-n"], {
      input: releaseBashRun(source, name),
      encoding: "utf8",
      timeout: 10_000,
      windowsHide: true,
    });
    assert.equal(result.error, undefined, result.error?.message);
    assert.equal(result.status, 0, `${name}\n${result.stdout ?? ""}\n${result.stderr ?? ""}`);
  }
}

test("release preflight and publication Bash are syntactically valid", assertReleaseBashSyntax);

/** 回归验证同一 workflow 在 Windows CRLF 与仓库 LF checkout 下都能被正确定位。 */
function assertWorkflowLineEndingNormalization() {
  const lf = readFileSync(RELEASE_WORKFLOW_PATH, "utf8").replace(/\r\n/gu, "\n");
  const crlf = lf.replace(/\n/gu, "\r\n");
  assert.equal(normalizeWorkflow(lf), normalizeWorkflow(crlf));
  assert.match(jobBody(normalizeWorkflow(crlf), "verify-release-candidate"), /Require current main/u);
}

test("workflow contract parser normalizes LF and CRLF", assertWorkflowLineEndingNormalization);

/** 固定快速门禁和桌面 reader 回归接线，防止测试遗漏或拆分后 native 提前启动。 */
function assertNativeVerificationGraph() {
  const source = readWorkflow(NATIVE_WORKFLOW_PATH);
  const fastChecks = jobBody(source, "fast-checks");
  const frontend = jobBody(source, "frontend-verification");
  const verification = jobBody(source, "fast-verification");
  const native = jobBody(source, "native-app-server");
  assert.match(fastChecks, /Run Python script tests[\s\S]*test_restore_verified_native/u);
  assert.match(fastChecks, /Run Node script tests[\s\S]*create-updater-manifest\.test\.mjs[\s\S]*workflow-contract\.test\.mjs/u);
  const nodeScripts = stepBody(fastChecks, "Run Node script tests");
  assert.match(nodeScripts, /node --test[\s\S]*scripts\/e2e\/windows-desktop-smoke\.test\.mjs[\s\S]*process-session-webview2\.test\.mjs/u);
  assert.match(fastChecks, /pnpm typecheck[\s\S]*pnpm lint[\s\S]*pnpm check:architecture[\s\S]*pnpm check:unused/u);
  assert.match(fastChecks, /cargo fmt --all -- --check/u);
  assert.match(frontend, /needs: fast-checks/u);
  assert.match(frontend, /playwright install chromium/u);
  assert.match(frontend, /pnpm typecheck:example:spring/u);
  assert.match(frontend, /pnpm test:example:spring/u);
  assert.match(frontend, /vitest run --maxWorkers=1 --minWorkers=1/u);
  assert.match(frontend, /turn-changes-card-browser\.mjs/u);
  assert.match(frontend, /vite build --config apps\/desktop\/vite\.config\.ts/u);
  assert.doesNotMatch(frontend, /^\s+pnpm build\s*$/mu);
  assert.match(verification, /needs: fast-checks/u);
  assert.match(verification, /tests\/contract\/run\.ps1/u);
  assert.match(verification, /playwright install chromium/u);
  assert.match(verification, /Run JVM verification and build the Rust test fixture/u);
  // PowerShell 调用 .cmd 时，带点的 Maven 属性必须整体引用，不能拆为 lifecycle phase。
  assert.match(verification, /"-Dja\.example\.browser=true"/u);
  assert.match(verification, /turn-change-artifact-rpc\.mjs/u);
  assert.match(verification, /process-session-webview2\.mjs/u);
  assert.match(verification, /cli-review-loopback\.mjs/u);
  assert.match(verification, /Run the Rust workspace gates/u);
  assert.match(native, /needs: \[fast-checks, frontend-verification, fast-verification\]/u);
  assert.match(native, /needs\.frontend-verification\.result == 'success'/u);
  assert.match(native, /needs\.fast-verification\.result == 'success'/u);
  assert.match(native, /windows-desktop-smoke\.mjs/u);
  assert.match(native, /run-windows-installer-smoke\.ps1/u);
  assert.match(native, /!cancelled\(\)/u);
  const npmSmoke = jobBody(source, "npm-package-verification");
  assert.match(npmSmoke, /needs: native-app-server/u);
  assert.match(npmSmoke, /npm install --offline --ignore-scripts/u);
  assert.match(npmSmoke, /verify-runtime\.mjs/u);
}

test("native matrix waits for both verification jobs", assertNativeVerificationGraph);

/** 固定成功主 CI 的精简 artifact 与恢复边界，并约束 SBOM 生产名称，避免原生编译成功后才发现缺文件。 */
function assertNativeReuseContract() {
  const source = readWorkflow(NATIVE_WORKFLOW_PATH);
  for (const platform of ["Windows", "macOS"]) {
    const producer = stepBody(source, `Generate CycloneDX SBOM (${platform})`);
    const report = stepBody(source, `Write checksum and build report (${platform})`);
    assert.match(producer, /-DoutputName=ja-app-server(?:\s|$)/u);
    assert.match(producer, /-DoutputFormat=json(?:\s|$)/u);
    assert.match(report, /ja-app-server\.json/u);
  }
  assert.match(source, /verified_run_id:[\s\S]*required: true[\s\S]*type: string/u);
  assert.match(source, /name: ja-native-reuse-\$\{\{ matrix\.platform \}\}-\$\{\{ matrix\.arch \}\}/u);
  assert.match(source, /ja-agent\/ja-app-server\/target\/ja-app-server\.exe/u);
  const windowsReuse = stepBody(source, "Prepare native reuse artifact (Windows)");
  const macReuse = stepBody(source, "Prepare native reuse artifact (macOS)");
  for (const reuseStep of [windowsReuse, macReuse]) {
    assert.match(reuseStep, /native-reuse/u);
    assert.match(reuseStep, /build-report\.json/u);
    assert.match(reuseStep, /ja-app-server\.json/u);
    assert.match(reuseStep, /native\/ja-agent\/ja-app-server\/target/u);
    assert.match(reuseStep, /native\/native-evidence/u);
  }
  for (const platform of ["Windows", "macOS"]) {
    const restore = stepBody(source, `Restore verified native executable (${platform})`);
    assert.match(restore, /\$inputRoot = Join-Path \$env:RUNNER_TEMP 'ja-verified-native\/native'/u);
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

/** 两端公开必须串联同一候选身份；npm 失败时 GitHub 仍保留可恢复的已验证草稿。 */
function assertCoordinatedPublication() {
  const source = readWorkflow(RELEASE_WORKFLOW_PATH);
  const npm = jobBody(source, "publish-npm-cli");
  assert.match(npm, /needs: \[verify-release-candidate, signed-native-matrix\]/u);
  assert.match(npm, /uses: \.\/\.github\/workflows\/npm-cli-publish\.yml/u);
  assert.match(npm, /source_commit: \$\{\{ needs\.verify-release-candidate\.outputs\.source_commit \}\}/u);
  assert.match(npm, /native_run_id: \$\{\{ needs\.verify-release-candidate\.outputs\.verified_run_id \}\}/u);
  assert.match(npm, /id-token: write/u);
  assert.match(npm, /NPM_TOKEN: \$\{\{ secrets\.NPM_TOKEN \}\}/u);
  const publication = jobBody(source, "publish-github-release");
  assert.match(publication, /needs: \[verify-release-candidate, signed-native-matrix, publish-npm-cli\]/u);
  assert.doesNotMatch(publication, /if:.*always\(\)|continue-on-error/u);
  assert.match(publication, /sourceCommit == \$sha/u);
  assert.match(publication, /sha256sum --check --strict SHA256SUMS/u);
  assert.match(publication, /gh release edit[\s\S]*--draft=false --latest/u);
  assert.match(publication, /\.draft == false and \.target_commitish == \$sha/u);
  const preflight = stepBody(source, "Require current main, verified CI artifacts, and release metadata");
  assert.match(preflight, /First npm publication requires the NPM_TOKEN repository secret/u);
}

test("release publishes npm before the verified GitHub draft", assertCoordinatedPublication);

/** npm 必须复用通过验收的持久 staging 依赖，不能读取打包后已清理的临时副本，并回读 registry 中的真实包。 */
function assertNpmPublicationContract() {
  const source = readWorkflow(NPM_WORKFLOW_PATH);
  assert.match(source, /workflow_call:[\s\S]*NPM_TOKEN:[\s\S]*required: false/u);
  assert.match(source, /workflow_dispatch:/u);
  assert.match(source, /id-token: write/u);
  assert.match(source, /cancel-in-progress: false/u);
  assert.match(source, /\$run\.event -cne 'push'/u);
  assert.match(source, /run-id: \$\{\{ inputs\.native_run_id \}\}/u);
  assert.match(source, /--search-tools \(Join-Path \$reuse 'tools'\)/u);
  const nativeSource = readWorkflow(NATIVE_WORKFLOW_PATH);
  for (const candidateStep of [
    stepBody(source, "Stage, inspect, and install the npm tarball"),
    stepBody(nativeSource, "Pack, install offline, and exercise the installed npm runtime"),
  ]) {
    assert.match(candidateStep, /--app-server \(Join-Path \$reuse 'native\/ja-agent\/ja-app-server\/target\/ja-app-server\.exe'\)/u);
    assert.match(candidateStep, /--native-report \(Join-Path \$reuse 'native\/native-evidence\/build-report\.json'\)/u);
    assert.match(candidateStep, /--search-tools \(Join-Path \$reuse 'tools'\)/u);
  }
  assert.match(source, /name: ja-npm-windows-x64/u);
  const candidate = stepBody(source, "Stage, inspect, and install the npm tarball");
  assert.match(candidate, /verify-runtime\.mjs[\s\S]*ja-npm-candidate-smoke/u);
  const publication = stepBody(source, "Publish and read back the verified public npm package");
  assert.match(publication, /NODE_AUTH_TOKEN: \$\{\{ secrets\.NPM_TOKEN \}\}/u);
  assert.match(publication, /\$failure\.error\.code -cne 'E404'/u);
  assert.match(publication, /npm publish --access public --registry=https:\/\/registry\.npmjs\.org/u);
  assert.match(publication, /npm install --ignore-scripts[\s\S]*\$spec --registry=/u);
  assert.match(publication, /verify-published\.mjs \$package \$stage/u);
  assert.match(publication, /verify-runtime\.mjs[\s\S]*ja-npm-published-smoke/u);
  const native = stepBody(readWorkflow(NATIVE_WORKFLOW_PATH), "Prepare native reuse artifact (Windows)");
  assert.match(native, /Copy-Item -Path \(Join-Path \$env:RUNNER_TEMP 'ja-native-stage\/sidecars\/tools\/\*'\)/u);
  assert.doesNotMatch(native, /Copy-Item -Path 'apps\/desktop\/src-tauri\/sidecars\/tools\/\*'/u);
}

test("npm publishes and verifies the installed registry package", assertNpmPublicationContract);
