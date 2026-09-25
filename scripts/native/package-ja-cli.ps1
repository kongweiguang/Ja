# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$SidecarArtifact,
    [Parameter(Mandatory)]
    [string]$BuildReport,
    [Parameter(Mandatory)]
    [string]$StageManifest,
    [string]$OutputDirectory = 'target/ja-cli',
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target'))

# 新鲜度以实际 Java 生产输入为界；只纳入 App Server 源码、JA-RPC 合同和版本权威文件。
function Get-LatestAppServerInputWriteTimeUtc {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)

    $inputFiles = @(
        Get-Item -LiteralPath (Join-Path $Root 'package.json') -ErrorAction Stop
        Get-Item -LiteralPath (Join-Path $Root 'app-server/pom.xml') -ErrorAction Stop
    )
    foreach ($relativeRoot in @('app-server/src/main', 'contracts/ja-rpc/v1')) {
        $sourceRoot = Join-Path $Root $relativeRoot
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
            throw "App Server 构建输入目录缺失：$relativeRoot"
        }
        $inputFiles += @(Get-ChildItem -LiteralPath $sourceRoot -File -Recurse -ErrorAction Stop)
    }
    if ($inputFiles.Count -lt 3) { throw 'App Server 构建输入清单为空' }
    return ($inputFiles | Sort-Object -Property LastWriteTimeUtc | Select-Object -Last 1).LastWriteTimeUtc
}

# 包目录只允许创建在 Cargo target 下；拒绝复用旧目录，避免静默替换用户已有的 CLI 包。
function Resolve-NewPackageOutputDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$TargetRoot,
        [Parameter(Mandatory)][string]$RequestedPath
    )

    $candidate = if ([System.IO.Path]::IsPathRooted($RequestedPath)) {
        [System.IO.Path]::GetFullPath($RequestedPath)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $Root $RequestedPath))
    }
    $targetPrefix = $TargetRoot + [System.IO.Path]::DirectorySeparatorChar
    if (
        $candidate -eq $TargetRoot -or
        -not $candidate.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        throw 'Ja CLI 输出目录必须是 target 下的新建目录'
    }
    if (Test-Path -LiteralPath $candidate) {
        throw 'Ja CLI 输出目录已存在；为避免覆盖旧资源，请选择新的输出目录'
    }
    return $candidate
}

# 必须把 Native Image 文件、通过 smoke 的 build report 和 copy-mode stage manifest 三方绑定，
# 并确认产物生成时间晚于本地 App Server 输入，不能从源码树的旧 sidecar 推断新构建成功。
function Assert-VerifiedNativeSidecar {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$TargetTriple,
        [Parameter(Mandatory)][string]$Platform,
        [Parameter(Mandatory)][string]$Architecture,
        [Parameter(Mandatory)][string]$NativeFileName,
        [Parameter(Mandatory)][string]$ResourceFileName,
        [Parameter(Mandatory)][string]$ArtifactPath,
        [Parameter(Mandatory)][string]$BuildReportPath,
        [Parameter(Mandatory)][string]$StageManifestPath
    )

    $artifact = Get-Item -LiteralPath $ArtifactPath -ErrorAction Stop
    $reportFile = Get-Item -LiteralPath $BuildReportPath -ErrorAction Stop
    $manifestFile = Get-Item -LiteralPath $StageManifestPath -ErrorAction Stop
    if ($artifact.Name -cne $NativeFileName) {
        throw "SidecarArtifact 必须是本轮 Native Image 产物 $NativeFileName；不能直接复用 apps/desktop/src-tauri/sidecars 资源"
    }
    if ($artifact.Length -le 0) { throw 'Native Image App Server 为空' }

    try {
        $report = Get-Content -LiteralPath $reportFile.FullName -Raw | ConvertFrom-Json
        $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json
        $native = $report.artifacts.nativeExecutable
        $sourceArtifact = $manifest.sidecar.sourceArtifact
        $stagedArtifact = $manifest.sidecar.stagedArtifact
    }
    catch {
        throw 'Native Image build report 或 sidecar stage manifest 无法解析'
    }

    $git = Get-Command git -ErrorAction Stop
    $currentCommit = (& $git.Source -C $Root rev-parse HEAD | Out-String).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $currentCommit -notmatch '^[0-9a-f]{40}$') {
        throw '无法确认当前仓库的源码 commit'
    }
    if (
        $report.schemaVersion -ne 1 -or
        $report.product -cne 'Ja' -or
        $report.sourceCommit -ine $currentCommit -or
        $report.target.platform -cne $Platform -or
        $report.target.arch -cne $Architecture -or
        $report.toolchain.nativeImageOnly -ne $true -or
        $report.toolchain.noFallback -ne $true -or
        $report.smoke.status -cne 'passed'
    ) {
        throw 'Native Image build report 未证明当前平台、源码版本及通过的 smoke'
    }
    if (
        $manifest.schemaVersion -ne 1 -or
        $manifest.product -cne 'Ja' -or
        $manifest.sourceCommit -ine $currentCommit -or
        $manifest.target.platform -cne $Platform -or
        $manifest.target.arch -cne $Architecture -or
        $manifest.target.targetTriple -cne $TargetTriple -or
        $manifest.nativeImageOnly -ne $true -or
        $manifest.noFallback -ne $true -or
        $manifest.stagingMode -cne 'copy' -or
        $manifest.sidecar.relativePath -cne "sidecars/$ResourceFileName"
    ) {
        throw 'sidecar stage manifest 与 CLI 目标平台或 copy 产物不匹配'
    }

    $artifactHash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if (
        $native.fileName -cne $NativeFileName -or
        [int64]$native.sizeBytes -ne [int64]$artifact.Length -or
        [string]$native.sha256 -ine $artifactHash -or
        $sourceArtifact.fileName -cne $NativeFileName -or
        [int64]$sourceArtifact.sizeBytes -ne [int64]$artifact.Length -or
        [string]$sourceArtifact.sha256 -ine $artifactHash -or
        $stagedArtifact.fileName -cne $ResourceFileName -or
        [int64]$stagedArtifact.sizeBytes -ne [int64]$artifact.Length -or
        [string]$stagedArtifact.sha256 -ine $artifactHash
    ) {
        throw 'Native Image artifact、build report 与 staged sidecar 的大小或 SHA256 不一致'
    }

    $latestInputWrite = Get-LatestAppServerInputWriteTimeUtc -Root $Root
    if ($artifact.LastWriteTimeUtc -lt $latestInputWrite) {
        throw 'Native Image App Server 早于当前 App Server 源码；请从当前源码重建并重新 smoke/stage'
    }
    if ($reportFile.LastWriteTimeUtc -lt $artifact.LastWriteTimeUtc) {
        throw 'Native Image build report 早于其 artifact，不能作为本轮构建证据'
    }
    if ($manifestFile.LastWriteTimeUtc -lt $reportFile.LastWriteTimeUtc) {
        throw 'sidecar stage manifest 早于 build report，不能作为本轮暂存证据'
    }

    return [pscustomobject]@{
        Artifact = $artifact
        Sha256 = $artifactHash
    }
}

$rustcDetails = & (Get-Command rustc -ErrorAction Stop).Source -vV 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw '无法读取 Rust host target' }
$targetMatch = [regex]::Match($rustcDetails, '(?m)^host:\s*(?<triple>\S+)\s*$')
if (-not $targetMatch.Success) { throw 'rustc -vV 未提供有效 host target' }
$targetTriple = $targetMatch.Groups['triple'].Value
$targets = @{
    'x86_64-pc-windows-msvc' = @{ Platform = 'windows'; Architecture = 'x86_64'; Extension = '.exe'; NativeName = 'ja-app-server.exe' }
    'aarch64-pc-windows-msvc' = @{ Platform = 'windows'; Architecture = 'aarch64'; Extension = '.exe'; NativeName = 'ja-app-server.exe' }
    'x86_64-apple-darwin' = @{ Platform = 'macos'; Architecture = 'x86_64'; Extension = ''; NativeName = 'ja-app-server' }
    'aarch64-apple-darwin' = @{ Platform = 'macos'; Architecture = 'aarch64'; Extension = ''; NativeName = 'ja-app-server' }
    'x86_64-unknown-linux-gnu' = @{ Platform = 'linux'; Architecture = 'x86_64'; Extension = ''; NativeName = 'ja-app-server' }
}
if (-not $targets.ContainsKey($targetTriple)) {
    throw "Ja CLI 当前没有为 host target $targetTriple 配置匹配的 App Server 资源"
}
$target = $targets[$targetTriple]
$cliName = "ja$($target.Extension)"
$sidecarName = "ja-app-server-$targetTriple$($target.Extension)"
$packageRoot = Resolve-NewPackageOutputDirectory -Root $repositoryRoot -TargetRoot $targetRoot -RequestedPath $OutputDirectory
$verifiedSidecar = Assert-VerifiedNativeSidecar `
    -Root $repositoryRoot `
    -TargetTriple $targetTriple `
    -Platform $target.Platform `
    -Architecture $target.Architecture `
    -NativeFileName $target.NativeName `
    -ResourceFileName $sidecarName `
    -ArtifactPath $SidecarArtifact `
    -BuildReportPath $BuildReport `
    -StageManifestPath $StageManifest

if ($ValidateOnly) {
    Write-Output "JA_CLI_SIDECAR_EVIDENCE_OK target=$targetTriple sidecar=$sidecarName sha256=$($verifiedSidecar.Sha256)"
    return
}

$buildRoot = Join-Path $targetRoot 'ja-cli-build'
$previousTargetDirectory = $env:CARGO_TARGET_DIR
$env:CARGO_TARGET_DIR = $buildRoot
try {
    Push-Location $repositoryRoot
    try {
        $cargo = Get-Command cargo -ErrorAction Stop
        & $cargo.Source build --release --locked -p ja-cli --bin ja
        if ($LASTEXITCODE -ne 0) { throw "Ja CLI 构建失败，退出码：$LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -eq $previousTargetDirectory) {
        Remove-Item Env:CARGO_TARGET_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:CARGO_TARGET_DIR = $previousTargetDirectory
    }
}

$cliSource = Join-Path (Join-Path $buildRoot 'release') $cliName
if (-not (Test-Path -LiteralPath $cliSource -PathType Leaf)) {
    throw "Cargo 未生成预期的 Ja CLI：$cliName"
}
if ((Get-FileHash -LiteralPath $verifiedSidecar.Artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $verifiedSidecar.Sha256) {
    throw 'Native Image App Server 在验证后发生变化，拒绝打包'
}

$stageRoot = Join-Path $targetRoot ('.ja-cli-package-stage-' + [guid]::NewGuid().ToString('N'))
$stageSidecars = Join-Path $stageRoot 'sidecars'
$stageCli = Join-Path $stageRoot $cliName
$stageSidecar = Join-Path $stageSidecars $sidecarName
$legalFiles = @(
    @{ Source = (Join-Path $repositoryRoot 'LICENSE'); Relative = 'LICENSE' },
    @{ Source = (Join-Path $repositoryRoot 'apps/cli/NOTICE.md'); Relative = 'NOTICE.md' },
    @{ Source = (Join-Path $repositoryRoot 'apps/cli/third_party/codex-LICENSE'); Relative = 'licenses/codex-LICENSE' }
)
try {
    New-Item -ItemType Directory -Path $stageSidecars -Force | Out-Null
    [System.IO.File]::Copy($cliSource, $stageCli, $false)
    [System.IO.File]::Copy($verifiedSidecar.Artifact.FullName, $stageSidecar, $false)
    foreach ($legal in $legalFiles) {
        $stagedLegal = Join-Path $stageRoot $legal.Relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $stagedLegal) -Force | Out-Null
        [System.IO.File]::Copy($legal.Source, $stagedLegal, $false)
    }

    $sourceCliHash = (Get-FileHash -LiteralPath $cliSource -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-FileHash -LiteralPath $stageCli -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sourceCliHash) {
        throw '暂存后的 Ja CLI 与 Cargo 产物不一致'
    }
    if ((Get-FileHash -LiteralPath $stageSidecar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $verifiedSidecar.Sha256) {
        throw '暂存后的 App Server 与经过验证的 Native Image 产物不一致'
    }

    New-Item -ItemType Directory -Path $packageRoot -ErrorAction Stop | Out-Null
    $outputSidecars = Join-Path $packageRoot 'sidecars'
    New-Item -ItemType Directory -Path $outputSidecars -ErrorAction Stop | Out-Null
    [System.IO.File]::Copy($stageCli, (Join-Path $packageRoot $cliName), $false)
    [System.IO.File]::Copy($stageSidecar, (Join-Path $outputSidecars $sidecarName), $false)
    foreach ($legal in $legalFiles) {
        $stagedLegal = Join-Path $stageRoot $legal.Relative
        $outputLegal = Join-Path $packageRoot $legal.Relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $outputLegal) -Force | Out-Null
        [System.IO.File]::Copy($stagedLegal, $outputLegal, $false)
        if ((Get-FileHash -LiteralPath $outputLegal -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath $legal.Source -Algorithm SHA256).Hash) {
            throw "本地 Ja CLI 包的许可证文件不匹配：$($legal.Relative)"
        }
    }

    $packagedCli = Get-Item -LiteralPath (Join-Path $packageRoot $cliName) -ErrorAction Stop
    $packagedSidecars = @(Get-ChildItem -LiteralPath $outputSidecars -File -Filter 'ja-app-server-*')
    if (
        $packagedSidecars.Count -ne 1 -or
        $packagedSidecars[0].Name -cne $sidecarName -or
        $packagedCli.Length -ne (Get-Item -LiteralPath $cliSource).Length -or
        (Get-FileHash -LiteralPath $packagedCli.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sourceCliHash -or
        $packagedSidecars[0].Length -ne $verifiedSidecar.Artifact.Length -or
        (Get-FileHash -LiteralPath $packagedSidecars[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $verifiedSidecar.Sha256
    ) {
        throw '本地 Ja CLI 包未通过可执行文件与 App Server 身份校验'
    }
}
finally {
    $resolvedStage = [System.IO.Path]::GetFullPath($stageRoot)
    $stagePrefix = $targetRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (
        $resolvedStage.StartsWith($stagePrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedStage -PathType Container)
    ) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}

Write-Output "JA_CLI_PACKAGE_OK target=$targetTriple executable=$cliName sidecar=sidecars/$sidecarName"
