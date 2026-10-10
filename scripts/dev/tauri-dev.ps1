# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$JavaHome = $(
        if ($env:JA_DEV_JAVA_HOME) {
            $env:JA_DEV_JAVA_HOME
        }
        else {
            'C:\Users\24052\.jdks\liberica-25.0.2'
        }
    ),
    [switch]$RunJavaTests,
    [switch]$SkipLaunch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

# 在调用任何构建工具前冻结仓库根，避免从子目录启动时解析到另一份 pom 或 workspace。
function Resolve-RepositoryRoot {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    if (-not (Test-Path -LiteralPath (Join-Path $root 'package.json') -PathType Leaf)) {
        throw "无法从脚本位置解析 Ja 仓库根目录：$root"
    }
    return $root
}

# 捕获版本命令的 stdout/stderr，并保留退出码，使工具链校验不会把不可执行命令误判为版本不匹配。
function Get-CheckedCommandOutput {
    param(
        [Parameter(Mandatory)]
        [string]$Command,
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    $output = & $Command @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（exit $LASTEXITCODE）：$Command $($Arguments -join ' ')`n$output"
    }
    return $output.Trim()
}

# 统一执行长时间外部命令并立即传播失败，防止 Java 构建失败后仍启动带旧 JAR 的桌面进程。
function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory)]
        [string]$Command,
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（exit $LASTEXITCODE）：$Command $($Arguments -join ' ')"
    }
}

# 显式锁定项目要求的 Java 25、Node 24 与 pnpm 10.33.0，避免桌面进程继承机器上的旧工具链。
function Assert-DevelopmentToolchain {
    param(
        [Parameter(Mandatory)]
        [string]$JavaExecutable
    )

    $javaVersion = Get-CheckedCommandOutput -Command $JavaExecutable -Arguments @('-version')
    if ($javaVersion -notmatch 'version "25(?:\.|"|\s)') {
        throw "Ja 开发启动必须使用 JDK 25，当前 java -version 为：`n$javaVersion"
    }

    $mavenVersion = Get-CheckedCommandOutput -Command 'mvn.cmd' -Arguments @('-version')
    if ($mavenVersion -notmatch '(?m)^Java version: 25(?:\.|,|\s)') {
        throw "Maven 未使用 JDK 25：`n$mavenVersion"
    }

    $nodeVersion = Get-CheckedCommandOutput -Command 'node.exe' -Arguments @('--version')
    if ($nodeVersion -notmatch '^v24\.') {
        throw "Ja 开发启动必须使用 Node 24.x，当前版本为：$nodeVersion"
    }

    $pnpmVersion = Get-CheckedCommandOutput -Command 'pnpm.cmd' -Arguments @('--version')
    if ($pnpmVersion -ne '10.33.0') {
        throw "Ja 开发启动必须使用 pnpm 10.33.0，当前版本为：$pnpmVersion"
    }

    Write-Host "工具链已确认：JDK 25 / $nodeVersion / pnpm $pnpmVersion" -ForegroundColor Green
}

# 运行副本不放进 Maven target，避免 clean/repackage 与仍在退出的 JVM 争用同一文件。
function New-DevelopmentRuntimeJar {
    param(
        [Parameter(Mandatory)]
        [string]$BuildJar,
        [Parameter(Mandatory)]
        [string]$RepositoryRoot
    )

    $directory = Join-Path $RepositoryRoot ".tmp\tauri-dev\$([Guid]::NewGuid().ToString('N'))"
    $null = New-Item -ItemType Directory -Path $directory
    $runtimeJar = Join-Path $directory 'ja-app-server.jar'
    Copy-Item -LiteralPath $BuildJar -Destination $runtimeJar
    return $runtimeJar
}

# 只回收本次创建的文件与空目录；残留 JVM 的锁不能使成功退出变失败，更不能触发强停。
function Remove-DevelopmentRuntimeJar {
    param(
        [Parameter(Mandatory)]
        [string]$RuntimeJar
    )

    try {
        Remove-Item -LiteralPath $RuntimeJar -ErrorAction Stop
        [IO.Directory]::Delete((Split-Path -Parent $RuntimeJar))
    }
    catch {
        Write-Warning "本次开发 JAR 暂未清理（可能仍被使用），不影响下次启动：$RuntimeJar"
    }
}

$repositoryRoot = Resolve-RepositoryRoot
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "找不到 JDK 25 java.exe：$javaExecutable。可通过 -JavaHome 或 JA_DEV_JAVA_HOME 指定。"
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"

Push-Location $repositoryRoot
$runtimeJar = $null
try {
    Assert-DevelopmentToolchain -JavaExecutable $javaExecutable

    $buildDirectory = 'target-tauri-dev'
    $mavenArguments = @('-B', '-ntp', '-f', 'ja-agent/pom.xml', '-pl', ':ja-app-server', '-am',
        "-Dja.build.directory=$buildDirectory", 'clean', 'package')
    if (-not $RunJavaTests) {
        $mavenArguments += '-DskipTests'
    }

    Write-Host '正在构建 Ja App Server JAR…' -ForegroundColor Cyan
    Invoke-CheckedCommand -Command 'mvn.cmd' -Arguments $mavenArguments

    $buildJar = (Resolve-Path -LiteralPath "ja-agent/ja-app-server/$buildDirectory/ja-app-server.jar").Path
    $runtimeJar = New-DevelopmentRuntimeJar -BuildJar $buildJar -RepositoryRoot $repositoryRoot
    $env:JA_DEBUG_JAVA = (Resolve-Path -LiteralPath $javaExecutable).Path
    $env:JA_DEBUG_JAR = $runtimeJar

    Write-Host "已启用 JVM 开发 sidecar：$runtimeJar" -ForegroundColor Green
    if ($SkipLaunch) {
        Write-Host '已按要求只完成 JAR 准备，未启动 Tauri。' -ForegroundColor Yellow
        return
    }

    Write-Host '正在启动 pnpm tauri dev…' -ForegroundColor Cyan
    Invoke-CheckedCommand -Command 'pnpm.cmd' -Arguments @('tauri', 'dev')
}
finally {
    if ($runtimeJar -and -not $SkipLaunch) {
        Remove-DevelopmentRuntimeJar -RuntimeJar $runtimeJar
    }
    Pop-Location
}
