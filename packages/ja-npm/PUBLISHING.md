<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja CLI npm 发布与本机验收

## 版本与发布身份

仓库根目录 `package.json` 是唯一产品版本来源。`pnpm version:sync` 与 `pnpm version:check` 同步和校验 `packages/ja-npm/package.json` 的版本；模板保持 `private: true`，staging 确认包名与版本后才移除私有标记，并把同一版本写入 `runtime/manifest.json`。不要直接发布这个私有模板。

npm 包名固定为 `@kongweiguang/ja`。首次公开发布需要账号侧完成 2FA 与初次包创建，然后为 `.github/workflows/npm-cli-publish.yml` 配置 npm Trusted Publisher。后续工作流只允许使用当前 `main` 的完整 commit SHA，以及同 SHA 的成功 `Ja App Server native matrix` 运行；发布使用 npm OIDC，不需要长效写入令牌。

## 门禁

从仓库根目录运行：

```powershell
pnpm version:check
node --test packages/ja-npm/tests/*.test.mjs
pwsh -NoProfile -File scripts/native/test_package_ja_cli.ps1
```

`scripts/native/package-ja-cli.ps1` 用通过 Native Image smoke 的 App Server、`build-report.json` 与 copy-mode `sidecar-manifest.json` 验证目标平台、commit、产物 hash 和输入新鲜度，然后构建 Rust CLI 并生成 `target/ja-cli`。输出目录必须不存在并位于仓库 `target` 下。

## 本地 npm tarball 安装

使用与当前代码相符的 Windows x64 CLI、Native Image App Server 及其通过 smoke 的报告。下面的名称只是本机测试 scope，不可用于公开发布：

```powershell
$commit = (git rev-parse HEAD).Trim()
$stage = 'target/ja-npm-local-stage'
$install = 'target/ja-npm-local-install'
node packages/ja-npm/scripts/stage.mjs --cli target/release/ja.exe --app-server app-server/target/ja-app-server.exe --native-report path/to/build-report.json --source-commit $commit --out $stage
if ($LASTEXITCODE -ne 0) { throw 'npm staging failed' }
Push-Location $stage
try {
  node bin/verify.cjs
  if ($LASTEXITCODE -ne 0) { throw 'package verification failed' }
  npm pack --dry-run
  if ($LASTEXITCODE -ne 0) { throw 'npm pack dry run failed' }
  $packed = npm pack --json | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0) { throw 'npm pack failed' }
  $archive = Join-Path (Get-Location) $packed[0].filename
} finally {
  Pop-Location
}
New-Item -ItemType Directory -Path $install | Out-Null
npm install --offline --ignore-scripts --no-audit --no-fund --prefix $install $archive
if ($LASTEXITCODE -ne 0) { throw 'clean npm install failed' }
$ja = Join-Path $install 'node_modules/.bin/ja.cmd'
& $ja --help
if ($LASTEXITCODE -ne 0) { throw 'installed Ja launcher failed' }
```

To view the interactive client without touching your normal Ja profile, set `USERPROFILE` and `HOME` to a fresh temporary directory before running `$ja`. The first launch may ask whether to trust the current project; review the displayed directory and choose explicitly.

Do not use local artifacts made from a dirty worktree as release evidence. The publish workflow checks out the selected `main` commit and downloads its matching native build evidence before it creates and installs the tarball.
