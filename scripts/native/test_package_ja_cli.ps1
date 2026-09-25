# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target'))
$testRoot = Join-Path $targetRoot ('.ja-cli-package-test-' + [guid]::NewGuid().ToString('N'))
$packageScript = Join-Path $PSScriptRoot 'package-ja-cli.ps1'

# 每个断言都明确对应一项防旧资源约束，失败时保留可读的门禁名称。
function Assert-PackageInvariant {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Name)

    if (-not $Condition) { throw "Ja CLI package self-test failed: $Name" }
}

# 负例必须在校验阶段退出，避免测试意外启动 Cargo 或留下半成品包。
function Assert-PackageRejected {
    param(
        [Parameter(Mandatory)][hashtable]$Arguments,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$ExpectedMessage
    )

    $rejected = $false
    $failureMessage = ''
    try {
        & $packageScript @Arguments | Out-Null
    }
    catch {
        $rejected = $true
        $failureMessage = $_.Exception.Message
    }
    Assert-PackageInvariant -Condition ($rejected -and $failureMessage.Contains($ExpectedMessage)) -Name $Name
}

# 原子内容由单个 JSON 文档写入；显式设置时间使证据先后关系不依赖文件系统时钟粒度。
function Write-JsonFixture {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][object]$Value,
        [Parameter(Mandatory)][datetime]$LastWriteTimeUtc
    )

    $json = ConvertTo-Json -InputObject $Value -Depth 16
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($Path, $LastWriteTimeUtc)
}

try {
    New-Item -ItemType Directory -Path $testRoot -ErrorAction Stop | Out-Null
    $rustcDetails = & (Get-Command rustc -ErrorAction Stop).Source -vV 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'rustc host target unavailable' }
    $targetMatch = [regex]::Match($rustcDetails, '(?m)^host:\s*(?<triple>\S+)\s*$')
    if (-not $targetMatch.Success) { throw 'rustc host target unavailable' }
    $targetTriple = $targetMatch.Groups['triple'].Value
    $target = @{
        'x86_64-pc-windows-msvc' = @{ Platform = 'windows'; Architecture = 'x86_64'; Extension = '.exe'; NativeName = 'ja-app-server.exe' }
        'aarch64-pc-windows-msvc' = @{ Platform = 'windows'; Architecture = 'aarch64'; Extension = '.exe'; NativeName = 'ja-app-server.exe' }
        'x86_64-apple-darwin' = @{ Platform = 'macos'; Architecture = 'x86_64'; Extension = ''; NativeName = 'ja-app-server' }
        'aarch64-apple-darwin' = @{ Platform = 'macos'; Architecture = 'aarch64'; Extension = ''; NativeName = 'ja-app-server' }
        'x86_64-unknown-linux-gnu' = @{ Platform = 'linux'; Architecture = 'x86_64'; Extension = ''; NativeName = 'ja-app-server' }
    }[$targetTriple]
    if ($null -eq $target) { throw "unsupported self-test target: $targetTriple" }

    $resourceName = "ja-app-server-$targetTriple$($target.Extension)"
    $artifactPath = Join-Path $testRoot $target.NativeName
    $reportPath = Join-Path $testRoot 'build-report.json'
    $manifestPath = Join-Path $testRoot 'stage/sidecar-manifest.json'
    $sbomPath = Join-Path $testRoot 'sbom.json'
    $smokePath = Join-Path $testRoot 'smoke.json'
    $checksumPath = Join-Path $testRoot 'ja-app-server.sha256'
    $artifactBytes = [System.Text.Encoding]::UTF8.GetBytes('fresh native app server fixture')
    [System.IO.File]::WriteAllBytes($artifactPath, $artifactBytes)
    $artifactHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $sourceCommit = (& (Get-Command git -ErrorAction Stop).Source -C $repositoryRoot rev-parse HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'git commit unavailable' }

    $sourceFiles = @(
        Get-Item -LiteralPath (Join-Path $repositoryRoot 'package.json')
        Get-Item -LiteralPath (Join-Path $repositoryRoot 'app-server/pom.xml')
        Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'app-server/src/main') -File -Recurse
        Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'contracts/ja-rpc/v1') -File -Recurse
    )
    $latestSourceTime = ($sourceFiles | Sort-Object -Property LastWriteTimeUtc | Select-Object -Last 1).LastWriteTimeUtc
    $artifactTime = [datetime]::UtcNow
    if ($artifactTime -le $latestSourceTime) { $artifactTime = $latestSourceTime }
    $artifactTime = $artifactTime.AddSeconds(2)
    [System.IO.File]::SetLastWriteTimeUtc($artifactPath, $artifactTime)
    Write-JsonFixture -Path $sbomPath -Value ([ordered]@{ bomFormat = 'CycloneDX' }) -LastWriteTimeUtc $artifactTime
    Write-JsonFixture -Path $smokePath -Value ([ordered]@{ status = 'passed' }) -LastWriteTimeUtc $artifactTime
    $python = Get-Command python -ErrorAction Stop
    $reportArguments = @(
        '-B', 'scripts/native/make-build-report.py',
        '--artifact', $artifactPath,
        '--sbom', $sbomPath,
        '--smoke', $smokePath,
        '--output', $reportPath,
        '--checksum-output', $checksumPath,
        '--platform', $target.Platform,
        '--arch', $target.Architecture,
        '--runner', 'local-selftest',
        '--source-commit', $sourceCommit,
        '--nik-version', 'selftest',
        '--nik-java-version', 'selftest',
        '--nik-sha256', ('a' * 64),
        '--no-fallback'
    )
    & $python.Source @reportArguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'native build report fixture generation failed' }
    $reportTime = [datetime]::UtcNow
    if ($reportTime -le $artifactTime) { $reportTime = $artifactTime.AddSeconds(1) }
    [System.IO.File]::SetLastWriteTimeUtc($reportPath, $reportTime)

    $stageArguments = @(
        '-B', 'scripts/native/stage-sidecar.py',
        '--artifact', $artifactPath,
        '--output-dir', (Join-Path $testRoot 'stage'),
        '--target-triple', $targetTriple,
        '--platform', $target.Platform,
        '--arch', $target.Architecture,
        '--source-commit', $sourceCommit,
        '--build-report', $reportPath
    )
    & $python.Source @stageArguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'sidecar stage manifest fixture generation failed' }
    $manifestTime = [datetime]::UtcNow
    if ($manifestTime -le $reportTime) { $manifestTime = $reportTime.AddSeconds(1) }
    [System.IO.File]::SetLastWriteTimeUtc($manifestPath, $manifestTime)

    $validOutput = Join-Path $testRoot 'valid-output'
    $validArguments = @{
        SidecarArtifact = $artifactPath
        BuildReport = $reportPath
        StageManifest = $manifestPath
        OutputDirectory = $validOutput
        ValidateOnly = $true
    }
    $result = & $packageScript @validArguments | Out-String
    Assert-PackageInvariant -Condition ($result.Contains('JA_CLI_SIDECAR_EVIDENCE_OK')) -Name 'fresh-build-report-and-stage-manifest-accepted'
    Assert-PackageInvariant -Condition (-not (Test-Path -LiteralPath $validOutput)) -Name 'validate-only-does-not-write'

    $existingOutput = Join-Path $testRoot 'existing-output'
    New-Item -ItemType Directory -Path $existingOutput | Out-Null
    $sentinel = Join-Path $existingOutput 'keep.txt'
    [System.IO.File]::WriteAllText($sentinel, 'preserve')
    $existingArguments = $validArguments.Clone()
    $existingArguments.OutputDirectory = $existingOutput
    Assert-PackageRejected -Arguments $existingArguments -Name 'existing-output-rejected' -ExpectedMessage '已存在'
    Assert-PackageInvariant -Condition ((Get-Content -LiteralPath $sentinel -Raw) -eq 'preserve') -Name 'existing-output-preserved'

    $staleResourceArguments = $validArguments.Clone()
    $staleResourceArguments.SidecarArtifact = Join-Path $repositoryRoot "apps/desktop/src-tauri/sidecars/$resourceName"
    $staleResourceArguments.OutputDirectory = Join-Path $testRoot 'stale-resource-output'
    Assert-PackageRejected -Arguments $staleResourceArguments -Name 'stale-tauri-resource-is-not-a-native-build-input' -ExpectedMessage '必须是本轮 Native Image 产物'

    [System.IO.File]::SetLastWriteTimeUtc($artifactPath, $latestSourceTime.AddDays(-1))
    Assert-PackageRejected -Arguments $validArguments -Name 'artifact-older-than-current-sources-rejected' -ExpectedMessage '早于当前 App Server 源码'

    [System.IO.File]::SetLastWriteTimeUtc($artifactPath, $artifactTime)
    $stageManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $stageManifest.sidecar.stagedArtifact.sha256 = ('0' * 64)
    Write-JsonFixture -Path $manifestPath -Value $stageManifest -LastWriteTimeUtc $manifestTime.AddSeconds(1)
    Assert-PackageRejected -Arguments $validArguments -Name 'staged-hash-mismatch-rejected' -ExpectedMessage 'SHA256 不一致'

    Write-Output 'JA_CLI_PACKAGE_SELFTEST_OK'
}
finally {
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $targetPrefix = $targetRoot + [System.IO.Path]::DirectorySeparatorChar
    if (
        $resolvedTestRoot.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedTestRoot -PathType Container)
    ) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
