# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

# 默认路径从当前脚本推导，复制工作区后不再调用旧副本或覆盖旧验收证据。
param(
    [string] $CodexSource = (Join-Path $PSScriptRoot '..\..\.tmp\verification\codex-cli-0.159.2'),
    [string] $EvidenceRoot = (Join-Path $PSScriptRoot '..\..\.tmp\verification\windows-terminal-parity'),
    [string] $JaExecutable = (Join-Path $PSScriptRoot '..\..\target\debug\ja.exe')
)

$ErrorActionPreference = 'Stop'

function Save-CommandOutput {
    <# 记录原始文字和退出码，只证明静态基线；真窗交互必须另存截图与操作证据。 #>
    param(
        [Parameter(Mandatory)] [string] $FileName,
        [Parameter(Mandatory)] [string[]] $Command
    )

    $arguments = @($Command | Select-Object -Skip 1)
    $output = @(& $Command[0] @arguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    [IO.File]::WriteAllLines((Join-Path $script:RunDirectory $FileName), $output, [Text.UTF8Encoding]::new($false))
    if ($exitCode -ne 0) { throw "$($Command[0]) exited with code $exitCode" }
}

function Get-GitCommit {
    <# 固定到安装版对应源码的提交，避免把可移动分支或较旧 checkout 当成视觉参考。 #>
    param([Parameter(Mandatory)] [string] $Repository)

    $commit = (& git -C $Repository rev-parse HEAD 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
        throw 'unable to resolve the Codex reference commit'
    }
    return $commit
}

$codex = Get-Command codex -ErrorAction Stop
$sourcePath = (Resolve-Path -LiteralPath $CodexSource).Path
$jaPath = (Resolve-Path -LiteralPath $JaExecutable).Path
$script:RunDirectory = Join-Path $EvidenceRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
[void](New-Item -ItemType Directory -Path $script:RunDirectory -Force)

Save-CommandOutput -FileName 'codex-version.txt' -Command @($codex.Source, '--version')
Save-CommandOutput -FileName 'codex-help.txt' -Command @($codex.Source, '--help')
Save-CommandOutput -FileName 'ja-version.txt' -Command @($jaPath, '--version')
Save-CommandOutput -FileName 'ja-help.txt' -Command @($jaPath, '--help')
$commit = Get-GitCommit -Repository $sourcePath
if ((Get-Content (Join-Path $script:RunDirectory 'codex-version.txt') -Raw).Trim() -ne 'codex-cli 0.159.2') {
    throw 'Codex reference must be the installed 0.159.2 version'
}
if ($commit -ne 'ff6aec96948b70d94983af2641a6b67c94faeff5') {
    throw 'Codex source must match rust-v0.159.2'
}
$metadata = @(
    "capturedAt=$([DateTimeOffset]::Now.ToString('o'))",
    "codexExecutable=$($codex.Source)",
    "codexSource=$sourcePath",
    "codexSourceCommit=$commit",
    "jaExecutable=$jaPath",
    "jaSha256=$((Get-FileHash -LiteralPath $jaPath -Algorithm SHA256).Hash)",
    'captureKind=static-command-baseline',
    'visualAcceptance=not-run',
    'interactiveAcceptance=not-run'
)
[IO.File]::WriteAllLines((Join-Path $script:RunDirectory 'baseline.txt'), $metadata, [Text.UTF8Encoding]::new($false))

Write-Output $script:RunDirectory
