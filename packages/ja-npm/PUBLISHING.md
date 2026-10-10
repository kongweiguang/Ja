<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja CLI npm 发布与本机验收

## 版本与发布身份

仓库根目录 `package.json` 是唯一产品版本来源。`pnpm version:sync` 与 `pnpm version:check` 同步和校验 `packages/ja-npm/package.json` 的版本；模板保持 `private: true`，staging 确认包名与版本后才移除私有标记，并把同一版本写入 `runtime/manifest.json`。不要直接发布这个私有模板。

npm 包名固定为 `@kongweiguang/ja`，安装命令为 `npm install -g @kongweiguang/ja`，启动命令为 `ja`。当前只支持 Windows x64 和 Node.js 24.x，包内携带 Rust CLI、原生 App Server、固定版本的 `fd` 与 `rg`，无需另装 JDK 或文件搜索工具。

## GitHub 同步发版

以后只需在 GitHub Actions 运行 **Release Ja**，填写当前 `main` 的完整 commit SHA。流程会查找同 SHA 成功的 `Ja App Server native matrix` push 运行，检查三个平台的原生产物和发布说明，再依次执行：

1. 复用已验证的 Native Image，生成带 updater 签名的桌面产物和 GitHub 草稿。
2. 构建同一源码的 Rust CLI，组装 npm 包，在干净目录安装并验证原生服务与文件搜索。
3. 公开发布 `@kongweiguang/ja`，从 npm registry 重新安装并校验版本、源码身份、运行时依赖和实际启动。
4. 核对 GitHub 草稿的源码、manifest 与校验和，再公开 GitHub Release。npm 失败时 GitHub 保持草稿。

版本修改后执行 `pnpm version:sync`，并准备 `scripts/release/notes/v<版本>.md`；拟发布源码需要先提交、推送到 `main` 并通过 CI。普通 CI 不公开发布。`.github/workflows/npm-cli-publish.yml` 同时保留手动入口，用于首次发布或单独恢复 npm；其 `native_run_id` 必须是同一 SHA 成功的原生 push CI 运行，不能填桌面发版的 workflow run ID。

### 首次 npm 发布

首次创建包使用临时 granular token，无需在本机手动编译和打包：

1. 在 npm 账号启用 2FA，创建拥有 `@kongweiguang` scope 读写权限、允许发布且启用 **Bypass 2FA** 的 granular token。限制有效期；如果 scope 是组织，还需账号本身有创建/发布该 scope 包的权限。
2. 将令牌存为 GitHub `kongweiguang/Ja` 仓库的 Actions Secret **NPM_TOKEN**。只保存在 Secret，不放进代码、文档或聊天。
3. 运行 **Release Ja**。首次包不存在且未配置令牌时，预检会在桌面签名前提示缺少 `NPM_TOKEN`。

写入令牌最长有效期为 90 天，没有永久有效的发布 Secret。npm 还将在 2027 年 1 月限制 Bypass 2FA 令牌直接发布，因此首次发布后应立即绑定下方的 Trusted Publisher；OIDC 每次运行自动领取短期凭据，不需要定期更换 Secret。令牌要求以 [npm 官方说明](https://docs.npmjs.com/creating-and-viewing-access-tokens/)为准。公开上传的包来自 Actions 中的实际构建及安装验收，不使用空包占位，也不使用本地未提交代码的产物。

### 后续 Trusted Publisher

首次成功后，在 npm 包 `@kongweiguang/ja` 的 Settings → Trusted Publisher 添加 GitHub Actions：

| 字段 | 内容 |
| --- | --- |
| Organization or user | `kongweiguang` |
| Repository | `Ja` |
| Workflow filename | `release.yml` |
| Environment name | 留空 |
| Allowed actions | 允许直接 `npm publish` |

集成发版由 `release.yml` 调用 reusable npm workflow，Trusted Publisher 必须匹配调用方 `release.yml`。如果还要使用独立 **Publish Ja CLI to npm** 手动入口，再添加一条 `npm-cli-publish.yml` 配置。使用 OIDC 成功后删除临时 `NPM_TOKEN` Secret 并撤销令牌；后续发布不需要长效令牌。配置细节与调用方匹配规则见 [npm 官方 Trusted Publisher 文档](https://docs.npmjs.com/trusted-publishers/)。

### 失败恢复

npm 版本不能覆盖。若该版本已发布，工作流先安装 registry 中的真实包，核对同一 commit、App Server 和搜索工具的 hash 后继续；相同版本属于其它源码或产物时停止，不把它当作成功。上传成功后也执行同样回读，再允许 GitHub 公开。

发布失败后，在原 **Release Ja** 运行中选择 **Re-run failed jobs**，保留成功的桌面矩阵与草稿；不要删除已发布 npm 版本。若只是首次认证未配置好，也可下载 npm job 保存的 `ja-npm-windows-x64` artifact，通过登录后的本机对其中真实 `.tgz` 执行 `npm publish <实际文件路径> --access public --registry=https://registry.npmjs.org`，再重跑失败 job。这个 artifact 只在 npm job 已完成打包与安装验证后产生；预检阶段失败不会产生包。首次公开成功后仍需完成 Trusted Publisher 配置。

## 门禁

从仓库根目录运行：

```powershell
pnpm version:check
node --test packages/ja-npm/tests/*.test.mjs
pwsh -NoProfile -File scripts/native/test_package_ja_cli.ps1
```

`scripts/native/package-ja-cli.ps1` 用通过 Native Image smoke 的 App Server、`build-report.json` 与 copy-mode `sidecar-manifest.json` 验证目标平台、commit、产物 hash 和输入新鲜度，然后构建 Rust CLI 并生成 `target/ja-cli`。输出目录必须不存在并位于仓库 `target` 下。

## 本地 npm tarball 安装

使用与当前代码相符的 Windows x64 CLI、Native Image App Server、通过 smoke 的报告，以及 `stage-search-tools.py` 生成的工具目录。下方是本地验收示例；`path/to/build-report.json` 和 `path/to/tools` 必须替换为真实路径，发布目录由 staging 生成，不能直接创建空目录后运行 `npm publish`：

```powershell
$commit = (git rev-parse HEAD).Trim()
$stage = 'target/ja-npm-local-stage'
$install = 'target/ja-npm-local-install'
node packages/ja-npm/scripts/stage.mjs --cli target/release/ja.exe --app-server ja-agent/ja-app-server/target/ja-app-server.exe --native-report path/to/build-report.json --search-tools path/to/tools --source-commit $commit --out $stage
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

上述 tarball 安装只验证 `--help` 启动器入口。正式 workflow 还会使用隔离配置运行原生服务与文件搜索 smoke；它会保留普通用户环境，并只为验收子进程选择临时用户数据目录。

不要把未提交工作区生成的本机包当成正式发布证据。正式 workflow 检出确定的 `main` commit，并下载相同 commit 的原生构建证据后才创建和安装 tarball。
