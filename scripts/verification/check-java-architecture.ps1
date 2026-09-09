# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$javaRoot = Join-Path $repositoryRoot 'app-server\src\main\java'
$violations = [System.Collections.Generic.List[object]]::new()
$observations = [System.Collections.Generic.List[object]]::new()

# Normalizes findings to repository-relative paths so evidence is portable and
# does not disclose the local Windows profile or checkout parent.
function Get-RepositoryRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    return [System.IO.Path]::GetRelativePath($repositoryRoot, $Path).Replace('\', '/')
}

# Records one bounded architecture finding; raw source content is deliberately
# excluded so credentials and user-authored paths cannot enter evidence JSON.
function Add-ArchitectureViolation {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Detail,
        [int]$Line = 0
    )

    $violations.Add([ordered]@{
        rule = $Rule
        path = Get-RepositoryRelativePath -Path $Path
        line = $Line
        detail = $Detail
    })
}

# Records responsibility-review signals separately from enforceable architecture contracts.
# File length alone cannot prove a design violation, but keeping the signal helps reviewers find
# likely split candidates without imposing an arbitrary line-count architecture.
function Add-ArchitectureObservation {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Detail,
        [int]$Line = 0
    )

    $observations.Add([ordered]@{
        rule = $Rule
        path = Get-RepositoryRelativePath -Path $Path
        line = $Line
        detail = $Detail
    })
}

# Applies a regular expression to production Java only. Exact exclusions are reserved for bootstrap
# owners that execute before the governed abstraction exists; callers must name every such file.
function Find-ProductionPattern {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Detail,
        [string]$Root = $javaRoot,
        [string[]]$ExcludedRelativePaths = @()
    )

    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.java' | ForEach-Object {
        $relativePath = Get-RepositoryRelativePath -Path $_.FullName
        if ($ExcludedRelativePaths -contains $relativePath) {
            return
        }
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($_.FullName)) {
            $lineNumber++
            if ($line -match $Pattern) {
                Add-ArchitectureViolation -Rule $Rule -Path $_.FullName -Line $lineNumber -Detail $Detail
                break
            }
        }
    }
}

if (-not (Test-Path -LiteralPath $javaRoot -PathType Container)) {
    throw 'Java production source root is missing.'
}

$productionFiles = @(Get-ChildItem -LiteralPath $javaRoot -Recurse -File -Filter '*.java')
foreach ($file in $productionFiles) {
    $head = (Get-Content -LiteralPath $file.FullName -TotalCount 12) -join "`n"
    if ($head -notmatch '@author\s+kongweiguang') {
        Add-ArchitectureViolation -Rule 'author' -Path $file.FullName -Detail 'production source is missing @author kongweiguang'
    }

    $lineCount = ([System.IO.File]::ReadLines($file.FullName) | Measure-Object).Count
    if ($lineCount -gt 500) {
        Add-ArchitectureObservation -Rule 'responsibility-size-review' -Path $file.FullName -Line $lineCount -Detail 'production source exceeds the review threshold; inspect responsibilities instead of treating line count as an automatic architecture failure'
    }
}

$agentRoot = Join-Path $javaRoot 'io\github\kongweiguang\ja\agent'
if (Test-Path -LiteralPath $agentRoot) {
    Find-ProductionPattern -Rule 'agent-dependency' -Root $agentRoot -Pattern '^\s*import\s+(com\.fasterxml|okhttp3|org\.apache\.ibatis|org\.noear\.solon|io\.modelcontextprotocol|io\.github\.kongweiguang\.ja\.(persistence|protocol|runtime|bootstrap))' -Detail 'agent layer imports framework, transport, or infrastructure code'
}

Find-ProductionPattern -Rule 'jdk-http' -Pattern '\bjava\.net\.http\b|\bHttpClient\.new(HttpClient|Builder)\b' -Detail 'production networking must use OkHttp only'
# Migration recovery validates a detached backup before MyBatis can safely open it. Keep this one
# bootstrap owner explicit; all normal persistence remains subject to the direct-JDBC prohibition.
Find-ProductionPattern -Rule 'direct-jdbc' -Pattern '\bDriverManager\b|\.prepareStatement\(|\.createStatement\(|\bResultSet\s+[A-Za-z_][A-Za-z0-9_]*\s*=' -Detail 'production persistence must use MyBatis mappers rather than manual JDBC execution or row mapping' -ExcludedRelativePaths @('app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/database/DatabaseMigrationRecovery.java')
Find-ProductionPattern -Rule 'obsolete-runtime' -Pattern '\b(StrictSseParser|RoutingModelPort|SingleWriter|SqliteSessionRepository|SqliteChangeStore|KernelRuntimeGraph|AtomicWorkspaceFiles)\b' -Detail 'obsolete production implementation remains reachable'
Find-ProductionPattern -Rule 'obsolete-wire' -Pattern '"(profile/(save|activate|read)|mcp/(save|delete)|change/revertSet|secret/resolve|diagnostics/read)"' -Detail 'removed first-release wire method remains in production source'
Find-ProductionPattern -Rule 'deferred-work' -Pattern '\b(TODO|FIXME|HACK|XXX)\b' -Detail 'deferred implementation marker is forbidden in final production source'

$result = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    productionFiles = $productionFiles.Count
    violationCount = $violations.Count
    observationCount = $observations.Count
    passed = $violations.Count -eq 0
    violations = @($violations)
    observations = @($observations)
}

$json = $result | ConvertTo-Json -Depth 8
if ($OutputPath) {
    # Preserve an explicitly absolute evidence path; joining it to the repository would create a
    # malformed nested path and make a fresh architecture result indistinguishable from missing.
    $resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        [System.IO.Path]::GetFullPath($OutputPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputPath))
    }
    $outputDirectory = Split-Path -Parent $resolvedOutput
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    [System.IO.File]::WriteAllText($resolvedOutput, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}
$json
if ($violations.Count -ne 0) {
    exit 1
}
