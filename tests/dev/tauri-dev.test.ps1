# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scriptPath = Join-Path $root 'scripts/dev/tauri-dev.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "Development script parse failed: $errors" }

# 加载真实函数而不执行 Maven/Tauri 主流程，使文件锁回归不依赖编译或用户运行中的应用。
foreach ($name in @('New-DevelopmentRuntimeJar', 'Remove-DevelopmentRuntimeJar')) {
    $definition = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if (-not $definition) { throw "Missing function: $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$directory = Join-Path $root ".tmp\tauri-dev-test-$([Guid]::NewGuid().ToString('N'))"
$null = New-Item -ItemType Directory -Path $directory
$buildJar = Join-Path $directory 'build.jar'
$first = $null
$second = $null
$lock = $null
try {
    [IO.File]::WriteAllText($buildJar, 'first-build')
    $first = New-DevelopmentRuntimeJar -BuildJar $buildJar -RepositoryRoot $directory
    $lock = [IO.File]::Open($first, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    [IO.File]::WriteAllText($buildJar, 'second-build')
    $second = New-DevelopmentRuntimeJar -BuildJar $buildJar -RepositoryRoot $directory
    if ($first -eq $second) { throw 'Runtime JAR paths were reused' }
    if ([IO.File]::ReadAllText($first) -ne 'first-build') { throw 'Locked runtime was changed' }
    if ([IO.File]::ReadAllText($second) -ne 'second-build') { throw 'Runtime did not receive fresh build' }
    Remove-DevelopmentRuntimeJar -RuntimeJar $first -WarningVariable warnings -WarningAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $first) -or @($warnings).Count -eq 0) {
        throw 'Locked runtime must be preserved with a warning'
    }
    $lock.Dispose()
    $lock = $null
    Remove-DevelopmentRuntimeJar -RuntimeJar $first
    if (Test-Path -LiteralPath (Split-Path -Parent $first)) { throw 'Released runtime was not cleaned' }
    Remove-DevelopmentRuntimeJar -RuntimeJar $second
    if (Test-Path -LiteralPath (Split-Path -Parent $second)) { throw 'Unused runtime was not cleaned' }
    Write-Output 'TAURI_DEV_JAR_TEST_OK unique-path fresh-build locked-cleanup released-cleanup'
}
finally {
    if ($lock) { $lock.Dispose() }
    # 此路径由本测试生成且必须仍是仓库临时目录下的直接子目录，禁止跨边界递归删除。
    $resolved = [IO.Path]::GetFullPath($directory)
    if ([IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $root '.tmp')) {
        throw 'Test cleanup escaped repository temp directory'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
