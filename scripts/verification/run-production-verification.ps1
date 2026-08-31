# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$EvidenceDirectory = '.tmp/verification/production',
    [string]$CargoTargetDirectory = '.tmp/cargo-production-verification',
    [string]$JavaHome = $(if (-not [string]::IsNullOrWhiteSpace($env:JA_E2E_JAVA_HOME)) {
        $env:JA_E2E_JAVA_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:JA_JAVA25_HOME)) {
        $env:JA_JAVA25_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $env:JAVA_HOME
    } else {
        ''
    }),
    [switch]$IncludeNative,
    [switch]$IncludeSoak,
    [ValidateRange(1, 1440)][int]$SoakDurationMinutes = 120,
    [switch]$IncludeDesktop,
    [switch]$IncludeRealProvider,
    [string]$CorrespondingSourcePath = '',
    [string[]]$ArtifactPath = @(),
    [switch]$ListOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidenceRoot = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) { $EvidenceDirectory } else { Join-Path $repositoryRoot $EvidenceDirectory }))
$cargoTarget = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($CargoTargetDirectory)) { $CargoTargetDirectory } else { Join-Path $repositoryRoot $CargoTargetDirectory }))
$steps = [System.Collections.Generic.List[object]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$runStartedAt = [DateTimeOffset]::Now
$evidencePreexisted = Test-Path -LiteralPath $evidenceRoot -PathType Container
$evidenceWasNonEmpty = $evidencePreexisted -and @((Get-ChildItem -LiteralPath $evidenceRoot -Force -ErrorAction SilentlyContinue)).Count -gt 0
$javaHomeIssue = $null
$javaExecutable = $null
$javaMajor = $null
$javaArtifactDirectory = Join-Path $evidenceRoot 'java-target'
$javaArtifactPath = Join-Path $javaArtifactDirectory 'ja-app-server.jar'
$mavenSurefireSnapshotDirectoryName = 'maven-surefire'
$mavenSurefireReportMaxBytes = [int64](16 * 1024 * 1024)
$mavenSurefireSnapshotMaxBytes = [int64](128 * 1024 * 1024)
$mavenSurefireSnapshot = [ordered]@{
    status = 'not-run'
    blocker = 'Maven Surefire snapshot did not execute'
    expectedCount = 0
    copiedCount = 0
    sourceReportCount = 0
    snapshotDirectory = $mavenSurefireSnapshotDirectoryName
}
$commonEnvironment = @{
    CARGO_TARGET_DIR = $cargoTarget
    JA_TEST_JAR = $javaArtifactPath
    JA_KERNEL_SMOKE_JAR = $javaArtifactPath
    JA_REAL_PROVIDER_JAR = $javaArtifactPath
    JA_E2E_APP_SERVER_JAR = $javaArtifactPath
}
$nativeRequested = [bool]$IncludeNative -or [bool]$IncludeSoak
$freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
# Approved hard stop: the verified 95,944,704-byte production artifact must remain within 100 MiB.
$nativeExecutableMaxBytes = [int64]104857600

# This closed set mirrors the Native smoke client.  The runner must inspect each capability
# independently because a top-level process/status pass cannot prove that every stop-ship path
# was exercised by the executable.
$requiredNativeSmokeSubgates = @(
    'jsonSchema', 'configAuth', 'okhttpSse', 'mcp',
    'shellCancellation', 'sqlite', 'recovery', 'networknt'
)

# Accepts only files produced after the relevant gate began and not implausibly in the future.
# The upper bound prevents copied or clock-forged artifacts from satisfying a current-run gate.
function Test-FreshTimestamp {
    param(
        [Parameter(Mandatory)][DateTime]$LastWriteTimeUtc,
        [Parameter(Mandatory)][DateTimeOffset]$Since
    )

    return $LastWriteTimeUtc -ge $Since.UtcDateTime `
        -and $LastWriteTimeUtc -le [DateTime]::UtcNow.Add($freshnessFutureSkew)
}

# Converts the filesystem timestamp to the same Unix nanosecond identity emitted by the Python
# smoke client. Keeping this value beside size and SHA-256 makes every caller prove it launched the
# exact artifact observed by the freshness gate, including a rebuild that reuses the same path.
function Get-FileMtimeNanoseconds {
    param([Parameter(Mandatory)][System.IO.FileInfo]$File)

    $unixEpochTicks = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc).Ticks
    return [int64](($File.LastWriteTimeUtc.Ticks - $unixEpochTicks) * 100L)
}

# Converts local paths and credential-like values to bounded placeholders before any diagnostic
# reaches an evidence file; command arguments remain structured objects and are never serialized
# with environment values. This is deliberately conservative because a failed provider command
# may echo more than the command itself knows about.
function ConvertTo-SafeDiagnosticText {
    param([AllowNull()][object]$Value)

    $text = [string]($Value ?? '')
    $sensitiveEnvironmentNames = 'API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH(?:ORIZATION)?|PASSWORD|SECRET|CREDENTIAL|PRIVATE[_-]?KEY'
    foreach ($entry in Get-ChildItem Env: -ErrorAction SilentlyContinue) {
        if ($entry.Name -match "(?i)$sensitiveEnvironmentNames" -and -not [string]::IsNullOrEmpty($entry.Value)) {
            $text = $text.Replace([string]$entry.Value, '<redacted>')
        }
    }

    $text = $text.Replace($repositoryRoot, '<repo>')
    $userProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    if (-not [string]::IsNullOrWhiteSpace($userProfile)) {
        $text = $text.Replace($userProfile, '<user>')
    }
    $text = [regex]::Replace($text, '(?im)(api[_ -]?key|access[_ -]?token|authorization|password|client[_ -]?secret|credential|private[_ -]?key)(\s*[:=]\s*|\s+)[^\s;,}]+', '$1=<redacted>')
    $text = [regex]::Replace($text, '(?i)\b(?:bearer\s+|sk-[a-z0-9_-]{8,}|gh[pousr]_[a-z0-9_-]{8,}|secret[-_][a-z0-9_-]+|token[-_][a-z0-9_-]+|password[-_][a-z0-9_-]+)\b', '<redacted>')
    $text = [regex]::Replace($text, '(?i)(?<![A-Za-z])(?:[A-Za-z]:\\|\\\\)[^\r\n\s]+', '<path>')
    if ($text.Length -gt 16384) {
        # Build tools publish dynamic verdicts at the end, while startup failures are usually at
        # the beginning. Preserve both bounded halves after redaction instead of hiding one class.
        $headLength = 8192
        $tailLength = 8192
        $text = $text.Substring(0, $headLength) + "`n<output-truncated>`n" `
            + $text.Substring($text.Length - $tailLength)
    }
    return $text
}

# Returns a stable repository-relative path for evidence while marking external caller paths
# explicitly. This keeps the report portable and prevents a username or secret-bearing temp path
# from becoming part of the evidence identity.
function Get-SafePath {
    param([Parameter(Mandatory)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = $repositoryRoot.TrimEnd('\') + '\'
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [System.IO.Path]::GetRelativePath($repositoryRoot, $full).Replace('\', '/')
    }
    return '<external>'
}

# Resolves one caller-selected JDK and validates it without persisting the version banner. The
# Production verification requires Java 25 specifically: PATH fallback is not accepted because a different
# JDK can silently change Maven, Solon AOT and the desktop child runtime.
function Initialize-Java25Environment {
    if ($ListOnly) { return }
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        $script:javaHomeIssue = 'JavaHome is required and must identify a Java 25 installation'
        return
    }
    try {
        $candidate = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $script:javaExecutable = $candidate
            $resolvedJavaHome = Split-Path -Parent (Split-Path -Parent $candidate)
        } elseif (Test-Path -LiteralPath $candidate -PathType Container) {
            $resolvedJavaHome = $candidate
            $script:javaExecutable = Join-Path $resolvedJavaHome 'bin\java.exe'
        } else {
            $script:javaHomeIssue = 'configured JavaHome path is neither a JDK directory nor java.exe'
            return
        }
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            $script:javaHomeIssue = 'configured JavaHome does not contain bin\java.exe'
            return
        }
        $versionOutput = @(& $javaExecutable '-version' 2>&1 | ForEach-Object { [string]$_ })
        $versionText = $versionOutput -join "`n"
        $versionMatch = [regex]::Match($versionText, '(?im)\bversion\s+"?(\d+)')
        if (-not $versionMatch.Success) {
            $versionMatch = [regex]::Match($versionText, '(?im)\bopenjdk\s+(\d+)')
        }
        if (-not $versionMatch.Success) {
            $script:javaHomeIssue = 'selected Java executable did not report a major version'
            return
        }
        $script:javaMajor = [int]$versionMatch.Groups[1].Value
        if ($javaMajor -ne 25) {
            $script:javaHomeIssue = 'selected Java executable is not major version 25'
            return
        }
        $commonEnvironment['JAVA_HOME'] = $resolvedJavaHome
        $commonEnvironment['JA_JAVA25_HOME'] = $resolvedJavaHome
        $commonEnvironment['JA_TEST_JAVA'] = $javaExecutable
        $commonEnvironment['JA_KERNEL_SMOKE_JAVA'] = $javaExecutable
        $commonEnvironment['JA_E2E_JAVA_HOME'] = $resolvedJavaHome
        # Existing smoke clients retain their narrow explicit seams while the common JA_TEST_JAVA
        # variable becomes the runner-wide source of truth for new consumers.
        $commonEnvironment['JA_REAL_PROVIDER_JAVA'] = $javaExecutable
    } catch {
        $script:javaHomeIssue = 'selected Java 25 executable could not be resolved or executed'
    }
}

# Returns only the selected Java identity and major version, never the version command output.
# This keeps a valid JDK auditable while preventing host paths or vendor banners from becoming
# portable evidence content.
function Get-Java25Evidence {
    if ($javaHomeIssue) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = $javaHomeIssue; javaMajor = $null; javaHome = $null; executable = $null }
    }
    if ($null -eq $javaExecutable -or $javaMajor -ne 25) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'Java 25 preflight did not execute'; javaMajor = $null; javaHome = $null; executable = $null }
    }
    return [ordered]@{ passed = $true; status = 'passed'; blocker = $null; javaMajor = $javaMajor; javaHome = Get-SafePath -Path (Split-Path -Parent (Split-Path -Parent $javaExecutable)); executable = Get-SafePath -Path $javaExecutable }
}

# Merges runner-wide Java/Cargo variables with a small per-gate override without mutating the
# shared hashtable. This prevents one provider or desktop invocation from leaking its mode into
# the next gate while preserving the exact selected JDK for every child process.
function Merge-Environment {
    param([Parameter(Mandatory)][hashtable]$Base, [Parameter(Mandatory)][hashtable]$Overrides)

    $merged = @{}
    foreach ($entry in $Base.GetEnumerator()) { $merged[$entry.Key] = $entry.Value }
    foreach ($entry in $Overrides.GetEnumerator()) { $merged[$entry.Key] = $entry.Value }
    return $merged
}

# Records one gate as data rather than composing a shell command; this is the source of truth for
# ListOnly and lets the runner report requested/skipped/blocked independently of execution order.
function Add-VerificationStep {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Requested,
        [Parameter(Mandatory)][ValidateSet('command', 'internal')][string]$Kind,
        [string]$Command = '',
        [string[]]$Arguments = @(),
        [hashtable]$Environment = @{},
        [string[]]$DependsOn = @(),
        [string[]]$RequiredInputs = @(),
        [bool]$PersistOutput = $true,
        [bool]$Optional = $false
    )

    $steps.Add([ordered]@{
        name = $Name
        requested = $Requested
        optional = $Optional
        kind = $Kind
        command = $Command
        arguments = @($Arguments)
        environment = $Environment
        dependsOn = @($DependsOn)
        requiredInputs = @($RequiredInputs)
        persistOutput = $PersistOutput
    })
}

# Converts only non-secret command arguments to a display form. The real invocation still receives
# the original argv array, while ListOnly remains safe to paste into a review without host paths.
function Get-DisplayArguments {
    param([Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Arguments)

    return @($Arguments | ForEach-Object {
        $value = [string]$_
        $value = $value.Replace($repositoryRoot, '<repo>')
        $value = $value.Replace($evidenceRoot, '<evidence>')
        $value = $value.Replace($cargoTarget, '<cargo-target>')
        if ([System.IO.Path]::IsPathRooted($value)) {
            $repositoryPrefix = $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
            $value = if ($value.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or $value.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                '<repo>' + $value.Substring($repositoryRoot.Length)
            } else {
                '<external>'
            }
        }
        if ($value -match '(?i)(api[_-]?key|token|secret|password|authorization|credential)') { '<redacted-arg>' } else { $value }
    })
}

# Writes only sanitized stdout/stderr and returns their portable filenames; raw child output is
# retained in memory until this function redacts it, so a provider echo cannot become a log file.
function Write-SanitizedCommandOutput {
    param(
        [Parameter(Mandatory)][string]$StepName,
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Stdout,
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Stderr,
        [Parameter(Mandatory)][bool]$PersistOutput
    )

    if (-not $PersistOutput) {
        return [ordered]@{ stdout = $null; stderr = $null }
    }
    $safeName = $StepName -replace '[^A-Za-z0-9._-]', '_'
    $stdoutPath = Join-Path $evidenceRoot "$safeName.stdout.log"
    $stderrPath = Join-Path $evidenceRoot "$safeName.stderr.log"
    [System.IO.File]::WriteAllText($stdoutPath, (ConvertTo-SafeDiagnosticText -Value ($Stdout -join [Environment]::NewLine)) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($stderrPath, (ConvertTo-SafeDiagnosticText -Value ($Stderr -join [Environment]::NewLine)) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    return [ordered]@{ stdout = [System.IO.Path]::GetRelativePath($evidenceRoot, $stdoutPath).Replace('\', '/'); stderr = [System.IO.Path]::GetRelativePath($evidenceRoot, $stderrPath).Replace('\', '/') }
}

# Invokes one command from the repository root with a structured argv array and temporary process
# environment. Separating this boundary makes cwd, environment restoration, redaction and failure
# identity testable without changing any production command entrypoint.
function Invoke-CommandGate {
    param([Parameter(Mandatory)]$Step)

    $startedAt = [DateTimeOffset]::Now
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $stdout = [System.Collections.Generic.List[string]]::new()
    $stderr = [System.Collections.Generic.List[string]]::new()
    $priorEnvironment = @{}
    $exitCode = 1
    $failureType = $null
    $failureCode = $null
    try {
        foreach ($entry in $Step.environment.GetEnumerator()) {
            $priorEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
        }
        $commandInfo = Get-Command -Name $Step.command -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $commandInfo) {
            $failureCode = 'command-unavailable'
            return [ordered]@{
                name = $Step.name; status = 'blocked'; requested = $Step.requested; optional = $Step.optional; executed = $false; passed = $false
                blocker = "required command is unavailable: $($Step.command)"; failureType = $null; exitCode = $null
                dependsOn = @($Step.dependsOn)
                startedAt = $startedAt.ToString('o'); durationMs = $watch.ElapsedMilliseconds; stdout = $null; stderr = $null
            }
        }
        Push-Location $repositoryRoot
        try {
            $capturedCharacters = 0
            $outputTruncated = $false
            & $Step.command @($Step.arguments) 2>&1 | ForEach-Object {
                $line = [string]$_
                $stdout.Add($line)
                $capturedCharacters += $line.Length + 2
                # Keep a bounded tail because Maven/Cargo/Vitest verdicts and dynamic counts are
                # emitted at the end. This also prevents a noisy child or Provider from consuming
                # unbounded controller memory before redaction.
                while ($capturedCharacters -gt 4MB -and $stdout.Count -gt 1) {
                    $capturedCharacters -= $stdout[0].Length + 2
                    $stdout.RemoveAt(0)
                    $outputTruncated = $true
                }
            }
            $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
            if ($outputTruncated) { $stdout.Insert(0, '<output-truncated-before-redaction>') }
        } finally {
            Pop-Location
        }
    } catch {
        $failureType = $_.Exception.GetType().Name
        $failureCode = 'command-start-or-runtime-error'
        $stderr.Add((ConvertTo-SafeDiagnosticText -Value $_.Exception.Message))
        $exitCode = 1
    } finally {
        $watch.Stop()
        foreach ($entry in $priorEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }

    $output = Write-SanitizedCommandOutput -StepName $Step.name -Stdout @($stdout) -Stderr @($stderr) -PersistOutput $Step.persistOutput
    $status = if ($exitCode -eq 0) { 'passed' } elseif ($exitCode -eq 2) { 'blocked' } else { 'failed' }
    return [ordered]@{
        name = $Step.name; status = $status; requested = $Step.requested; optional = $Step.optional; executed = $true; passed = $exitCode -eq 0
        blocker = if ($exitCode -eq 0) { $null } elseif ($exitCode -eq 2) { 'blocked-exit-code-2' } else { $failureCode ?? "exit-code-$exitCode" }; failureType = $failureType; exitCode = $exitCode
        dependsOn = @($Step.dependsOn)
        startedAt = $startedAt.ToString('o'); durationMs = $watch.ElapsedMilliseconds; stdout = $output.stdout; stderr = $output.stderr
    }
}

# Computes the contract corpus digest and physical frame counts using the same name/byte order as
# the Python gate. A digest derived from current files prevents a stale consumer result from being
# mistaken for evidence about a changed protocol corpus.
function Get-ContractCorpusEvidence {
    $goldenRoot = Join-Path $repositoryRoot 'contracts\golden'
    if (-not (Test-Path -LiteralPath $goldenRoot -PathType Container)) {
        return [ordered]@{ status = 'blocked'; blocker = 'contract corpus directory is missing'; toolDigest = $null; toolValidFrames = $null; toolInvalidFrames = $null }
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $validCount = 0
    $invalidCount = 0
    $fileCount = 0
    try {
        foreach ($file in @(Get-ChildItem -LiteralPath $goldenRoot -Recurse -File | Where-Object { $_.Extension -in @('.json', '.jsonl') } | Sort-Object { [System.IO.Path]::GetRelativePath($goldenRoot, $_.FullName).Replace('\', '/') })) {
            $relative = [System.IO.Path]::GetRelativePath($goldenRoot, $file.FullName).Replace('\', '/')
            $relativeBytes = [System.Text.Encoding]::UTF8.GetBytes($relative)
            $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
            $sha.TransformBlock($relativeBytes, 0, $relativeBytes.Length, $null, 0) | Out-Null
            $sha.TransformBlock([byte[]](0), 0, 1, $null, 0) | Out-Null
            if ($bytes.Length -gt 0) { $sha.TransformBlock($bytes, 0, $bytes.Length, $null, 0) | Out-Null }
            $sha.TransformBlock([byte[]](0), 0, 1, $null, 0) | Out-Null
            $fileCount++
            $isInvalid = $relative -match '(^|/)invalid(/|/)'
            $records = if ($file.Extension -eq '.json') { 1 } else { @([System.IO.File]::ReadLines($file.FullName) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count }
            if ($isInvalid) { $invalidCount += $records } else { $validCount += $records }
        }
        $sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
        $digest = ($sha.Hash | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha.Dispose()
    }
    return [ordered]@{ status = 'passed'; digest = $digest; fileCount = $fileCount; validFrames = $validCount; invalidFrames = $invalidCount; toolMode = $null; toolTerminals = $null; toolDigest = $null; toolValidFrames = $null; toolInvalidFrames = $null }
}

# Discovers the runtime's Surefire directories and separates all reports from those fresh for the
# current gate. Keeping this identity check in one function prevents the post-package summary from
# accidentally reading a different target layout than the pre-package snapshot step.
function Get-MavenSurefireReports {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $targetRoot = Join-Path $repositoryRoot 'app-server\target'
    $reportRoots = @()
    if (Test-Path -LiteralPath $targetRoot -PathType Container) {
        $reportRoots = @(Get-ChildItem -LiteralPath $targetRoot -Directory -Filter 'surefire-reports' -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Unique)
    }
    $all = @(foreach ($reportRoot in $reportRoots) {
        Get-ChildItem -LiteralPath $reportRoot.FullName -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue
    })
    return [ordered]@{
        all = $all
        fresh = @($all | Where-Object { Test-FreshTimestamp -LastWriteTimeUtc $_.LastWriteTimeUtc -Since $Since })
        roots = $reportRoots
    }
}

# Parses Surefire's console aggregate for builds that intentionally set useFile=false. Per-class
# markers are retained only as observedSuites; they can be truncated by bounded command capture and
# therefore must never be reported as the exact suite count.
function Get-MavenSurefireTextEvidence {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)

    $summaryPattern = 'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)'
    $records = [System.Collections.Generic.List[object]]::new()
    $observedSuites = 0
    $resultsMarkerSeen = $false
    $aggregate = $null
    foreach ($line in @($Text -split "`r?`n")) {
        if ($line -match '\s-- in\s+\S+') { $observedSuites++ }
        if ($line -match '^\s*\[INFO\]\s*Results:\s*$') {
            $resultsMarkerSeen = $true
            continue
        }
        $match = [regex]::Match($line, $summaryPattern)
        if (-not $match.Success) { continue }
        $record = [ordered]@{
            tests = [int]$match.Groups[1].Value
            failures = [int]$match.Groups[2].Value
            errors = [int]$match.Groups[3].Value
            skipped = [int]$match.Groups[4].Value
        }
        $records.Add($record)
        if ($resultsMarkerSeen -and $null -eq $aggregate) { $aggregate = $record }
    }

    if ($null -eq $aggregate -and $records.Count -eq 1) { $aggregate = $records[0] }
    if ($null -eq $aggregate) {
        return [ordered]@{ parsed = $false; suites = $null; observedSuites = $observedSuites; tests = 0; failures = 0; errors = 0; skipped = 0 }
    }
    return [ordered]@{
        parsed = $true
        suites = $null
        observedSuites = $observedSuites
        tests = $aggregate.tests
        failures = $aggregate.failures
        errors = $aggregate.errors
        skipped = $aggregate.skipped
    }
}

# Copies fresh XML before any later package gate can clean or replace the Maven target. Every file
# is copied through a temporary sibling, bounded by per-file/total sizes, rechecked for source
# mutation, and finally moved into a dedicated evidence directory; any ambiguity fails closed.
function Save-MavenSurefireSnapshot {
    param([Parameter(Mandatory)]$GateResult)

    $gateStartedAt = $null
    if ($GateResult -is [System.Collections.IDictionary] -and $GateResult.Contains('startedAt')) {
        $gateStartedAt = $GateResult['startedAt']
    } elseif ($GateResult.PSObject.Properties['startedAt']) {
        $gateStartedAt = $GateResult.startedAt
    }
    $gateSince = $runStartedAt
    if ($gateStartedAt) {
        try { $gateSince = [DateTimeOffset]::Parse([string]$gateStartedAt) } catch { $gateSince = $runStartedAt }
    }
    $reportSet = Get-MavenSurefireReports -Since $gateSince
    $snapshotRoot = Join-Path $evidenceRoot $mavenSurefireSnapshotDirectoryName
    $base = [ordered]@{
        status = 'absent'
        blocker = $null
        expectedCount = 0
        copiedCount = 0
        sourceReportCount = @($reportSet.all).Count
        snapshotDirectory = $mavenSurefireSnapshotDirectoryName
        freshnessReference = $gateSince.ToString('o')
    }
    if (@($reportSet.all).Count -eq 0) { return $base }
    if (@($reportSet.fresh).Count -eq 0 -or @($reportSet.fresh).Count -ne @($reportSet.all).Count) {
        $base.status = 'stale'
        $base.blocker = 'Maven Surefire report set is stale or incomplete'
        $base.expectedCount = @($reportSet.fresh).Count
        return $base
    }
    $base.expectedCount = @($reportSet.fresh).Count
    $names = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($report in @($reportSet.fresh)) {
        if (-not $names.Add($report.Name)) {
            $base.status = 'failed'
            $base.blocker = 'Maven Surefire snapshot contains duplicate report basenames'
            return $base
        }
    }
    if (Test-Path -LiteralPath $snapshotRoot) {
        if (@(Get-ChildItem -LiteralPath $snapshotRoot -Force -ErrorAction SilentlyContinue).Count -gt 0) {
            $base.status = 'failed'
            $base.blocker = 'Maven Surefire snapshot directory is not empty'
            return $base
        }
    } else {
        [System.IO.Directory]::CreateDirectory($snapshotRoot) | Out-Null
    }
    $created = [System.Collections.Generic.List[string]]::new()
    $totalBytes = [int64]0
    try {
        foreach ($report in @($reportSet.fresh)) {
            $sourceBefore = Get-Item -LiteralPath $report.FullName -ErrorAction Stop
            if ([int64]$sourceBefore.Length -gt $mavenSurefireReportMaxBytes) { throw 'report-too-large' }
            $totalBytes += [int64]$sourceBefore.Length
            if ($totalBytes -gt $mavenSurefireSnapshotMaxBytes) { throw 'snapshot-too-large' }
            $temporary = Join-Path $snapshotRoot ('.' + [Guid]::NewGuid().ToString('N') + '.tmp')
            $destination = Join-Path $snapshotRoot $report.Name
            [System.IO.File]::Copy($sourceBefore.FullName, $temporary, $false)
            $sourceAfter = Get-Item -LiteralPath $report.FullName -ErrorAction Stop
            $copy = Get-Item -LiteralPath $temporary -ErrorAction Stop
            if ($sourceAfter.Length -ne $sourceBefore.Length -or $sourceAfter.LastWriteTimeUtc -ne $sourceBefore.LastWriteTimeUtc `
                    -or $copy.Length -ne $sourceBefore.Length) { throw 'report-mutated-during-copy' }
            [System.IO.File]::SetLastWriteTimeUtc($temporary, $sourceBefore.LastWriteTimeUtc)
            [System.IO.File]::Move($temporary, $destination)
            $created.Add($destination)
        }
        $base.status = 'copied'
        $base.blocker = $null
        $base.copiedCount = $created.Count
        return $base
    } catch {
        foreach ($path in @($created)) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        Get-ChildItem -LiteralPath $snapshotRoot -Filter '*.tmp' -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
        $base.status = 'failed'
        $base.blocker = 'Maven Surefire snapshot copy failed'
        $base.copiedCount = $created.Count
        return $base
    }
}

# Reads only the pre-package Surefire snapshot after the Java gate. A truly XML-free build may use
# fresh gate console output, but a stale/failed snapshot never falls back because that would hide a
# target cleanup or partial-copy defect behind an apparently successful Maven exit code.
function Get-MavenTestEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $gate = $results | Where-Object name -eq 'java-jvm' | Select-Object -First 1
    $gateSince = $Since
    $gateStartedAt = $null
    if ($null -ne $gate -and $gate -is [System.Collections.IDictionary] -and $gate.Contains('startedAt')) {
        $gateStartedAt = $gate['startedAt']
    } elseif ($null -ne $gate -and $gate.PSObject.Properties['startedAt']) {
        $gateStartedAt = $gate.startedAt
    }
    if ($gateStartedAt) {
        try { $gateSince = [DateTimeOffset]::Parse([string]$gateStartedAt) } catch { $gateSince = $Since }
    }
    $snapshot = $mavenSurefireSnapshot
    $snapshotStatus = if ($snapshot -is [System.Collections.IDictionary] -and $snapshot.Contains('status')) { [string]$snapshot['status'] } else { 'not-run' }
    $totals = [ordered]@{
        status = 'blocked'
        blocker = 'no fresh Maven Surefire report or gate output'
        source = $null
        suites = $null
        observedSuites = $null
        tests = 0
        failures = 0
        errors = 0
        skipped = 0
        snapshot = $snapshot
    }
    $reports = @()
    $snapshotBlocker = $null
    if ($snapshotStatus -eq 'copied') {
        $snapshotRoot = Join-Path $evidenceRoot $mavenSurefireSnapshotDirectoryName
        $reports = @(Get-ChildItem -LiteralPath $snapshotRoot -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
        $expectedCount = if ($snapshot.Contains('expectedCount')) { [int]$snapshot['expectedCount'] } else { -1 }
        if (-not (Test-Path -LiteralPath $snapshotRoot -PathType Container) -or $expectedCount -lt 1 -or $reports.Count -ne $expectedCount) {
            $snapshotBlocker = 'Maven Surefire snapshot is incomplete'
        } elseif (@($reports | Where-Object { -not (Test-FreshTimestamp -LastWriteTimeUtc $_.LastWriteTimeUtc -Since $gateSince) }).Count -gt 0) {
            $snapshotBlocker = 'Maven Surefire snapshot is stale or future-dated'
        }
    } elseif ($snapshotStatus -in @('stale', 'failed', 'not-run')) {
        if ($snapshotStatus -ne 'not-run' -or ($null -ne $gate -and $gate.passed)) {
            $snapshotBlocker = if ($snapshot.blocker) { [string]$snapshot.blocker } else { 'Maven Surefire snapshot did not complete' }
        }
    }
    $malformedReport = $false
    if ($null -eq $snapshotBlocker -and $reports.Count -gt 0) {
        foreach ($report in $reports) {
            try {
                [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
                $suite = $document.testsuite
                $totals.tests += [int]$suite.tests
                $totals.failures += [int]$suite.failures
                $totals.errors += [int]$suite.errors
                $totals.skipped += [int]$suite.skipped
                $totals.suites++
            } catch {
                $malformedReport = $true
                $totals.blocker = 'one fresh Maven Surefire report is malformed'
            }
        }
        $totals.source = 'surefire-xml-snapshot'
        if (-not $malformedReport) { $totals.status = 'passed'; $totals.blocker = $null }
    } elseif ($null -eq $snapshotBlocker -and $snapshotStatus -eq 'absent') {
        $stdoutRelative = ''
        if ($null -ne $gate -and $gate -is [System.Collections.IDictionary] -and $gate.Contains('stdout')) {
            $stdoutRelative = [string]$gate['stdout']
        } elseif ($null -ne $gate -and $gate.PSObject.Properties['stdout']) {
            $stdoutRelative = [string]$gate.stdout
        }
        $stdoutPath = if ([string]::IsNullOrWhiteSpace($stdoutRelative)) { $null } else { Join-Path $evidenceRoot $stdoutRelative }
        $stdoutFresh = $null -ne $stdoutPath -and (Test-Path -LiteralPath $stdoutPath -PathType Leaf) `
            -and (Test-FreshTimestamp -LastWriteTimeUtc (Get-Item -LiteralPath $stdoutPath).LastWriteTimeUtc -Since $gateSince)
        if ($stdoutFresh) {
            $console = Get-MavenSurefireTextEvidence -Text (Get-Content -LiteralPath $stdoutPath -Raw)
            if ($console.parsed) {
                $totals.status = 'passed'
                $totals.blocker = $null
                $totals.source = 'maven-gate-output'
                $totals.suites = $null
                $totals.observedSuites = $console.observedSuites
                $totals.tests = $console.tests
                $totals.failures = $console.failures
                $totals.errors = $console.errors
                $totals.skipped = $console.skipped
                $totals.output = [System.IO.Path]::GetRelativePath($evidenceRoot, $stdoutPath).Replace('\', '/')
            }
        }
    } elseif ($snapshotBlocker) {
        $totals.blocker = $snapshotBlocker
    }
    if ($totals.failures -gt 0 -or $totals.errors -gt 0) { $totals.status = 'failed'; $totals.blocker = 'fresh Maven reports contain failures or errors' }
    elseif ($null -eq $gate -or -not $gate.executed) { $totals.status = 'blocked'; $totals.blocker = 'Maven test gate did not execute' }
    elseif (-not $gate.passed) { $totals.status = 'failed'; $totals.blocker = 'Maven test gate failed before completing the full suite' }
    return $totals
}

# Reads a possibly absent JSON numeric field without letting StrictMode turn a partial reporter
# fixture into a runner exception; absent counters remain explicit zero values for review.
function Get-JsonIntField {
    param([Parameter(Mandatory)]$Document, [Parameter(Mandatory)][string]$Name)

    $property = $Document.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return 0 }
    return [int]$property.Value
}

# Parses a Vitest JSON reporter output file and preserves its tool-provided dynamic totals. The
# file is intentionally required to be newer than this run so a prior green report cannot pass.
function Get-VitestTestEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $gate = $results | Where-Object name -eq 'typescript-tests' | Select-Object -First 1
    if ($null -eq $gate -or -not $gate.executed) {
        return [ordered]@{ status = 'blocked'; blocker = 'Vitest gate did not execute' }
    }
    $path = Join-Path $evidenceRoot 'vitest.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [ordered]@{ status = 'blocked'; blocker = 'Vitest JSON reporter output is missing' }
    }
    $file = Get-Item -LiteralPath $path
    if (-not (Test-FreshTimestamp -LastWriteTimeUtc $file.LastWriteTimeUtc -Since $Since)) {
        return [ordered]@{ status = 'blocked'; blocker = 'Vitest JSON reporter output is stale' }
    }
    try {
        $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $failed = (Get-JsonIntField -Document $document -Name 'numFailedTests') + (Get-JsonIntField -Document $document -Name 'numFailedTestSuites')
        $passed = $failed -eq 0 -and $gate.passed
        return [ordered]@{ status = if ($passed) { 'passed' } else { 'failed' }; blocker = if ($passed) { $null } elseif (-not $gate.passed) { 'Vitest gate failed before completing the full suite' } else { 'Vitest JSON reports failed tests' }; testSuites = Get-JsonIntField -Document $document -Name 'numTotalTestSuites'; tests = Get-JsonIntField -Document $document -Name 'numTotalTests'; passed = Get-JsonIntField -Document $document -Name 'numPassedTests'; failed = Get-JsonIntField -Document $document -Name 'numFailedTests'; skipped = Get-JsonIntField -Document $document -Name 'numPendingTests'; todo = Get-JsonIntField -Document $document -Name 'numTodoTests' }
    } catch {
        return [ordered]@{ status = 'blocked'; blocker = 'Vitest JSON reporter output is malformed' }
    }
}

# Extracts Cargo's real test-result lines from sanitized logs, retaining passed/failed/ignored
# counts without depending on a particular Cargo JSON schema or writing raw test output to JSON.
function Get-RustTestEvidence {
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($result in $results | Where-Object { $_.name -in @('rust-runtime-tests', 'rust-tauri-tests', 'rust-host-integration') }) {
        # PowerShell's regex operator writes the case-insensitive automatic `$Matches` variable.
        # A distinct name is required here or the first result line replaces this typed list.
        $testResults = [System.Collections.Generic.List[object]]::new()
        foreach ($logName in @($result.stdout, $result.stderr)) {
            if ([string]::IsNullOrWhiteSpace($logName)) { continue }
            $path = Join-Path $evidenceRoot $logName
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
            foreach ($line in Get-Content -LiteralPath $path) {
                if ($line -match 'test result:\s+(ok|FAILED)\.\s+(\d+) passed;\s+(\d+) failed;\s+(\d+) ignored;\s+(\d+) measured;\s+(\d+) filtered out') {
                    $testResults.Add([ordered]@{ status = $Matches[1].ToLowerInvariant(); passed = [int]$Matches[2]; failed = [int]$Matches[3]; ignored = [int]$Matches[4]; measured = [int]$Matches[5]; filtered = [int]$Matches[6] })
                }
            }
        }
        $entries.Add([ordered]@{ name = $result.name; status = if ($testResults.Count -gt 0 -and @($testResults | Where-Object { $_.failed -gt 0 }).Count -eq 0 -and $result.passed) { 'passed' } elseif ($testResults.Count -eq 0) { 'blocked' } else { 'failed' }; results = @($testResults) })
    }
    return @($entries)
}

# Parses the contract gate's stable marker and combines it with a freshly computed corpus digest;
# child consumer paths and any provider-like data are intentionally excluded from evidence.
function Get-ContractTestEvidence {
    $result = $results | Where-Object name -eq 'contract' | Select-Object -First 1
    $evidence = Get-ContractCorpusEvidence
    if ($null -eq $result) { $evidence.status = 'blocked'; $evidence.blocker = 'contract step did not execute'; return $evidence }
    foreach ($logName in @($result.stdout, $result.stderr)) {
        if ([string]::IsNullOrWhiteSpace($logName)) { continue }
        $path = Join-Path $evidenceRoot $logName
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        foreach ($line in Get-Content -LiteralPath $path) {
            if ($line -match 'GOLDEN_OK\s+validFrames=(\d+)\s+invalidFrames=(\d+)\s+positiveFiles=(\d+)\s+negativeFiles=(\d+)\s+terminals=(\d+)\s+digest=([0-9a-f]{64})') {
                $evidence.toolTerminals = [int]$Matches[5]
            }
            if ($line -match 'CONTRACT_GATE_OK\s+mode=(\S+)\s+digest=([0-9a-f]{64})\s+validFrames=(\d+)\s+invalidFrames=(\d+)') {
                $evidence.toolMode = $Matches[1]
                $evidence.toolDigest = $Matches[2]
                $evidence.toolValidFrames = [int]$Matches[3]
                $evidence.toolInvalidFrames = [int]$Matches[4]
            }
        }
    }
    if (-not $result.passed) { $evidence.status = 'failed'; $evidence.blocker = 'contract command failed' }
    elseif ($evidence.toolDigest -ne $evidence.digest -or $evidence.toolValidFrames -ne $evidence.validFrames -or $evidence.toolInvalidFrames -ne $evidence.invalidFrames) { $evidence.status = 'failed'; $evidence.blocker = 'contract tool marker does not match current corpus' }
    return $evidence
}

# Hashes expected production artifacts and records size plus freshness. Required artifacts are
# linked to their producing gates; optional artifacts are explicit skipped entries rather than an
# absent file being treated as a successful native or desktop build.
function Get-ArtifactEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $configuredJavaArtifact = Get-Variable -Name javaArtifactPath -ErrorAction SilentlyContinue
    $effectiveJavaArtifactPath = if ($null -ne $configuredJavaArtifact) {
        [string]$configuredJavaArtifact.Value
    } else {
        Join-Path $repositoryRoot 'app-server\target\ja-app-server.jar'
    }
    $specs = @(
        [ordered]@{ key = 'javaJar'; path = [System.IO.Path]::GetRelativePath($repositoryRoot, $effectiveJavaArtifactPath).Replace('\', '/'); required = $true; producedBy = 'java-artifact-package'; maxBytes = 268435456 },
        [ordered]@{ key = 'nativeExe'; path = 'app-server/target/ja-app-server.exe'; required = $nativeRequested; producedBy = 'java-native-build'; maxBytes = $nativeExecutableMaxBytes },
        [ordered]@{ key = 'frontendIndex'; path = 'dist/index.html'; required = $true; producedBy = 'desktop-build'; maxBytes = 104857600 }
    )
    $artifacts = [System.Collections.Generic.List[object]]::new()
    foreach ($spec in $specs) {
        $path = Join-Path $repositoryRoot ($spec.path.Replace('/', '\'))
        $exists = Test-Path -LiteralPath $path -PathType Leaf
        if (-not $spec.required) {
            $actualBytes = if ($exists) { [int64](Get-Item -LiteralPath $path).Length } else { $null }
            $artifacts.Add([ordered]@{ key = $spec.key; path = $spec.path; required = $false; status = 'skipped'; passed = $false; exists = $exists; freshThisRun = $false; actualBytes = $actualBytes; bytes = $actualBytes; sha256 = $null; mtimeNs = $null; maxBytes = $spec.maxBytes; producedBy = $spec.producedBy })
            continue
        }
        if (-not $exists) {
            $artifacts.Add([ordered]@{ key = $spec.key; path = $spec.path; required = $true; status = 'blocked'; passed = $false; exists = $false; freshThisRun = $false; actualBytes = $null; bytes = $null; sha256 = $null; mtimeNs = $null; maxBytes = $spec.maxBytes; producedBy = $spec.producedBy })
            continue
        }
        $file = Get-Item -LiteralPath $path
        $producer = $results | Where-Object name -eq $spec.producedBy | Select-Object -First 1
        $producerStartedAt = if ($null -ne $producer -and -not [string]::IsNullOrWhiteSpace($producer.startedAt)) { [DateTimeOffset]::Parse($producer.startedAt) } else { $null }
        # Freshness is tied to the successful producing gate, not merely to the overall runner
        # clock. This rejects a stale or future-dated artifact when packaging never executed.
        $fresh = $null -ne $producer -and $producer.passed -and $null -ne $producerStartedAt `
            -and (Test-FreshTimestamp -LastWriteTimeUtc $file.LastWriteTimeUtc -Since $producerStartedAt)
        $bytes = [int64]$file.Length
        $passed = $fresh -and $bytes -le $spec.maxBytes
        $artifacts.Add([ordered]@{ key = $spec.key; path = $spec.path; required = $true; status = if ($passed) { 'passed' } else { 'failed' }; passed = $passed; exists = $true; freshThisRun = $fresh; producerPassed = [bool]($null -ne $producer -and $producer.passed); actualBytes = $bytes; bytes = $bytes; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); mtimeNs = Get-FileMtimeNanoseconds -File $file; lastWriteTime = $file.LastWriteTime.ToString('o'); maxBytes = $spec.maxBytes; producedBy = $spec.producedBy })
    }
    return @($artifacts)
}

# Identifies only repository build/cache roots; source and the current evidence directory are never
# filtered by this helper. Cargo targets can contain generated JavaScript and copied dependency
# sources, so scanning them as authored changes both duplicates coverage and makes summary time
# proportional to compiler cache size.
function Test-GeneratedRepositoryPath {
    param([Parameter(Mandatory)][string]$RelativePath)

    $path = $RelativePath.Replace('\', '/')
    if ($path.StartsWith('./', [System.StringComparison]::Ordinal)) { $path = $path.Substring(2) }
    return $path -match '^(?:\.tmp|dist|node_modules|target(?:-[^/]+)?|src-tauri/target(?:-[^/]+)?|app-server/target|apps/desktop/dist)(?:/|$)'
}

# Scans changed source and this run's evidence for credential-shaped literals while returning only
# path/line/rule metadata. It intentionally does not print matching source text or values.
function Get-SecretGateEvidence {
    $findings = [System.Collections.Generic.List[object]]::new()
    $paths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($line in @(git -C $repositoryRoot diff --name-only --diff-filter=AM 2>$null)) {
        if ($line -and -not (Test-GeneratedRepositoryPath -RelativePath ([string]$line))) {
            $paths.Add((Join-Path $repositoryRoot ([string]$line))) | Out-Null
        }
    }
    foreach ($line in @(git -C $repositoryRoot ls-files --others --exclude-standard 2>$null)) {
        if ($line -and -not (Test-GeneratedRepositoryPath -RelativePath ([string]$line))) {
            $paths.Add((Join-Path $repositoryRoot ([string]$line))) | Out-Null
        }
    }
    if (Test-Path -LiteralPath $evidenceRoot -PathType Container) { foreach ($file in Get-ChildItem -LiteralPath $evidenceRoot -Recurse -File -ErrorAction SilentlyContinue) { $paths.Add($file.FullName) | Out-Null } }
    foreach ($path in $paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $extension = [System.IO.Path]::GetExtension($path).ToLowerInvariant()
        if ($extension -in @('.png', '.jpg', '.jpeg', '.gif', '.ico', '.exe', '.dll', '.jar', '.db', '.sqlite', '.woff', '.woff2')) { continue }
        try {
            $lineNumber = 0
            foreach ($line in [System.IO.File]::ReadLines($path)) {
                $lineNumber++
                $knownToken = $line -match '(?i)\b(?:sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9_-]{12,}|bearer\s+[a-z0-9._-]{12,})\b'
                $assignedSecret = $false
                if ($line -match '(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password|authorization)\s*[:=]\s*["'']([^"'']{16,})["'']') {
                    $candidate = $Matches[2]
                    $assignedSecret = $candidate -notmatch '(?i)(dummy|placeholder|not[_ -]?a[_ -]?secret|example|fixture|smoke|test|redacted)'
                }
                if ($knownToken -or $assignedSecret) {
                    $findings.Add([ordered]@{ path = Get-SafePath -Path $path; line = $lineNumber; rule = 'credential-shaped-literal' })
                }
            }
        } catch { $findings.Add([ordered]@{ path = Get-SafePath -Path $path; line = 0; rule = 'unreadable-secret-scan-input' }) }
    }
    return [ordered]@{ passed = $findings.Count -eq 0; findingCount = $findings.Count; findings = @($findings) }
}

# Checks only product executables at their exact resolved paths; it never name-matches unrelated
# processes and never records command lines. A surviving child is a residual-resource blocker.
function Get-ResidualProcessEvidence {
    $candidatePaths = @(
        (Join-Path $repositoryRoot 'app-server\target\ja-app-server.exe'),
        (Join-Path $repositoryRoot 'src-tauri\target\debug\ja.exe'),
        (Join-Path $repositoryRoot 'src-tauri\target\release\ja.exe')
    ) | ForEach-Object { [System.IO.Path]::GetFullPath($_) }
    $count = 0
    try {
        foreach ($row in @(Get-CimInstance Win32_Process -ErrorAction Stop)) {
            if ($row.ExecutablePath -and $candidatePaths -contains ([System.IO.Path]::GetFullPath([string]$row.ExecutablePath))) { $count++ }
        }
    } catch {
        return [ordered]@{ passed = $false; blocker = 'process residual snapshot unavailable'; residualProcessCount = $null }
    }
    return [ordered]@{ passed = $count -eq 0; blocker = if ($count -eq 0) { $null } else { 'owned Ja executable process remains after gates' }; residualProcessCount = $count }
}

# Enforces explicit artifact size budgets and required freshness without pretending missing build
# outputs are green. Thresholds are recorded with each artifact so a future review can change the
# budget deliberately instead of hiding it in a count assertion.
function Get-SizeGateEvidence {
    param([Parameter(Mandatory)][object[]]$Artifacts)

    $findings = [System.Collections.Generic.List[object]]::new()
    foreach ($artifact in $Artifacts | Where-Object required) {
        if (-not $artifact.exists) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-missing' }); continue }
        if (-not $artifact.freshThisRun) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-stale' }) }
        if ([int64]$artifact.actualBytes -gt [int64]$artifact.maxBytes) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-too-large' }) }
    }
    return [ordered]@{ passed = $findings.Count -eq 0; findingCount = $findings.Count; findings = @($findings) }
}

# Verifies the Native Image profile carries the required no-fallback flag in the actual Maven
# composition root. This gate is separate so a successful Maven invocation cannot be misread as a
# no-fallback build when the profile was changed or omitted.
function Get-NativePolicyEvidence {
    $pomPath = Join-Path $repositoryRoot 'app-server\pom.xml'
    if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) { return [ordered]@{ passed = $false; blocker = 'Native Image Maven POM is missing' } }
    $text = Get-Content -LiteralPath $pomPath -Raw
    $matches = @([regex]::Matches($text, '<buildArg>\s*--no-fallback\s*</buildArg>'))
    return [ordered]@{ passed = $matches.Count -eq 1; noFallbackFlagCount = $matches.Count; blocker = if ($matches.Count -eq 1) { $null } else { 'Native Image profile must contain exactly one --no-fallback build argument' } }
}

# Prevents a prior run's logs or reporter files from being mistaken for this invocation's proof;
# an empty pre-created directory is allowed, while a non-empty one is a hard blocker.
function Get-EvidenceFreshnessEvidence {
    return [ordered]@{ passed = -not $evidenceWasNonEmpty; blocker = if ($evidenceWasNonEmpty) { 'evidence directory is not fresh; choose a new directory' } else { $null }; preexisted = $evidencePreexisted; wasNonEmpty = $evidenceWasNonEmpty }
}

# Claims an empty evidence directory with CreateNew before any child gate writes to it. Two
# concurrent runners targeting the same directory cannot merge logs into a falsely fresh report.
function New-EvidenceRunClaim {
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $claimPath = Join-Path $evidenceRoot '.production-verification-run.json'
    try {
        $stream = [System.IO.File]::Open($claimPath, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
        try {
            $claim = [ordered]@{ schemaVersion = 1; repository = 'ja'; startedAt = $runStartedAt.ToString('o') }
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes(($claim | ConvertTo-Json -Compress) + [Environment]::NewLine)
            $stream.Write($bytes, 0, $bytes.Length)
        } finally {
            $stream.Dispose()
        }
        return $true
    } catch [System.IO.IOException] {
        return $false
    }
}

# Requires the Native Image executable to be produced by the current no-fallback build before the
# smoke step can run. Returning the actual size/hash keeps the smoke dependency tied to a fresh
# artifact rather than to a stale executable left by an earlier build.
function Get-NativeArtifactFreshnessEvidence {
    $artifacts = @(Get-ArtifactEvidence -Since $runStartedAt | Where-Object { $_.key -eq 'nativeExe' })
    if ($artifacts.Count -ne 1) { return [ordered]@{ passed = $false; blocker = 'native artifact evidence was not produced' } }
    $artifact = $artifacts[0]
    $identityComplete = -not [string]::IsNullOrWhiteSpace([string]$artifact.sha256) `
        -and [int64]$artifact.actualBytes -gt 0 `
        -and [int64]$artifact.mtimeNs -gt 0
    $passed = [bool]$artifact.passed -and $identityComplete
    return [ordered]@{ passed = $passed; blocker = if ($passed) { $null } elseif (-not $identityComplete) { 'fresh native artifact identity is incomplete' } else { 'fresh no-fallback native executable is missing, stale, or exceeds the size budget' }; artifact = $artifact }
}

# Validates the smoke report's required capability closure. Missing/blocked entries stay distinct
# from failed entries so evidence explains whether a capability was not exercised or contradicted
# its own status; neither category is allowed to satisfy the Native gate.
function Get-NativeSmokeSubgateEvidence {
    param(
        [Parameter(Mandatory)]$Report,
        [Parameter(Mandatory)][string[]]$RequiredSubgates
    )

    $missing = [System.Collections.Generic.List[string]]::new()
    $blocked = [System.Collections.Generic.List[string]]::new()
    $failed = [System.Collections.Generic.List[string]]::new()
    $subgatesProperty = $Report.PSObject.Properties['subgates']
    $subgates = if ($null -ne $subgatesProperty) { $subgatesProperty.Value } else { $null }
    foreach ($name in $RequiredSubgates) {
        $entryProperty = if ($null -ne $subgates) { $subgates.PSObject.Properties[$name] } else { $null }
        if ($null -eq $entryProperty -or $null -eq $entryProperty.Value) {
            $missing.Add($name)
            continue
        }
        $entry = $entryProperty.Value
        $statusProperty = $entry.PSObject.Properties['status']
        if ($null -eq $statusProperty) {
            $missing.Add($name)
            continue
        }
        $status = [string]$statusProperty.Value
        if ($status -eq 'passed') {
            $passedProperty = $entry.PSObject.Properties['passed']
            if ($null -ne $passedProperty -and $passedProperty.Value -ne $true) {
                $failed.Add($name)
            }
        } elseif ($status -in @('blocked', 'skipped')) {
            $blocked.Add($name)
        } else {
            $failed.Add($name)
        }
    }
    $status = if ($failed.Count -gt 0) { 'failed' } elseif ($missing.Count -gt 0 -or $blocked.Count -gt 0) { 'blocked' } else { 'passed' }
    return [ordered]@{
        status = $status
        passed = $missing.Count -eq 0 -and $blocked.Count -eq 0 -and $failed.Count -eq 0
        required = @($RequiredSubgates)
        missing = @($missing)
        blocked = @($blocked)
        failed = @($failed)
    }
}

# Reads the smoke report produced for this run and requires the smoke client to confirm the exact
# three-value identity supplied by the freshness gate. A successful process exit alone is not proof:
# a missing or tampered identity report remains a hard failure in the production summary.
function Get-NativeSmokeIdentityEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    if (-not $nativeRequested) {
        return [ordered]@{ status = 'skipped'; blocker = $null; required = $false; expectedIdentityMatched = $null; artifactIdentityMatched = $null; requiredSubgates = $null }
    }
    $gate = $results | Where-Object name -eq 'java-native-smoke' | Select-Object -First 1
    $path = Join-Path $evidenceRoot 'native-smoke.json'
    if ($null -eq $gate -or -not $gate.executed) {
        return [ordered]@{ status = 'blocked'; blocker = 'native smoke gate did not execute'; required = $true; expectedIdentityMatched = $false; artifactIdentityMatched = $false; requiredSubgates = $null }
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [ordered]@{ status = 'blocked'; blocker = 'native smoke identity report is missing'; required = $true; expectedIdentityMatched = $false; artifactIdentityMatched = $false; requiredSubgates = $null }
    }
    $reportFile = Get-Item -LiteralPath $path
    if (-not (Test-FreshTimestamp -LastWriteTimeUtc $reportFile.LastWriteTimeUtc -Since $Since)) {
        return [ordered]@{ status = 'blocked'; blocker = 'native smoke identity report is stale'; required = $true; expectedIdentityMatched = $false; artifactIdentityMatched = $false; requiredSubgates = $null }
    }
    try {
        $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $executableProperty = $report.PSObject.Properties['executable']
        $executable = if ($null -ne $executableProperty) { $executableProperty.Value } else { $null }
        $matchedProperty = if ($null -ne $executable) { $executable.PSObject.Properties['expectedIdentityMatched'] } else { $null }
        $expectedIdentityMatched = $null -ne $matchedProperty -and $matchedProperty.Value -eq $true
        $reportPassedProperty = $report.PSObject.Properties['passed']
        $reportPassed = $null -ne $reportPassedProperty -and $reportPassedProperty.Value -eq $true
        $subgateEvidence = Get-NativeSmokeSubgateEvidence -Report $report -RequiredSubgates $requiredNativeSmokeSubgates
        $artifact = (Get-NativeArtifactFreshnessEvidence).artifact
        $artifactIdentityMatched = $null -ne $executable `
            -and [string]$executable.sha256 -eq [string]$artifact.sha256 `
            -and [int64]$executable.sizeBytes -eq [int64]$artifact.actualBytes `
            -and [int64]$executable.mtimeNs -eq [int64]$artifact.mtimeNs
        $passed = $gate.passed -and $report.status -eq 'passed' -and $reportPassed `
            -and $expectedIdentityMatched -and $artifactIdentityMatched -and $subgateEvidence.passed
        $blocker = if ($passed) { $null }
        elseif (-not $expectedIdentityMatched) { 'native smoke report did not prove expected artifact identity' }
        elseif (-not $artifactIdentityMatched) { 'native smoke report identity differs from freshness evidence' }
        elseif (-not $subgateEvidence.passed -and $subgateEvidence.status -eq 'blocked') { 'native smoke required subgate is blocked or missing' }
        elseif (-not $subgateEvidence.passed) { 'native smoke required subgate failed or was mutated' }
        elseif (-not $reportPassed) { 'native smoke report did not prove top-level passed state' }
        else { 'native smoke report is not passed' }
        return [ordered]@{ status = if ($passed) { 'passed' } elseif ($subgateEvidence.status -eq 'blocked') { 'blocked' } else { 'failed' }; blocker = $blocker; required = $true; expectedIdentityMatched = $expectedIdentityMatched; artifactIdentityMatched = $artifactIdentityMatched; requiredSubgates = $subgateEvidence }
    } catch {
        return [ordered]@{ status = 'blocked'; blocker = 'native smoke identity report is malformed'; required = $true; expectedIdentityMatched = $false; artifactIdentityMatched = $false; requiredSubgates = $null }
    }
}

# Checks provider prerequisites by name only. The values are never copied into a step, exception,
# output stream or summary; when credentials are absent the real-provider gate is an explicit block.
function Get-ProviderPreflightEvidence {
    $required = @(
        'JA_REAL_PROVIDER_OPENAI_API_KEY', 'JA_REAL_PROVIDER_OPENAI_BASE_URL', 'JA_REAL_PROVIDER_OPENAI_MODEL',
        'JA_REAL_PROVIDER_ANTHROPIC_API_KEY', 'JA_REAL_PROVIDER_ANTHROPIC_BASE_URL', 'JA_REAL_PROVIDER_ANTHROPIC_MODEL'
    )
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) })
    $authorized = [Environment]::GetEnvironmentVariable('JA_REAL_PROVIDER_AUTHORIZED', 'Process') -eq '1'
    return [ordered]@{ passed = $missing.Count -eq 0 -and $authorized; authorized = $authorized; missingVariables = $missing; blocker = if (-not $authorized) { 'real provider execution was not explicitly authorized for this verification run' } elseif ($missing.Count -ne 0) { 'provider-specific credentials, loopback URLs or model names are not present in the process environment' } else { $null } }
}

# Validates the legal SBOM inputs before the mature generator is allowed to run. Both input
# categories are intentionally all-or-none, absolute, repository-scoped and fresh for this run;
# this prevents a stale or private path from becoming release evidence and keeps a missing legal
# input a hard prerequisite for Native rather than a generator default.
function Get-SbomInputEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $sourceProvided = -not [string]::IsNullOrWhiteSpace($CorrespondingSourcePath)
    $artifactArguments = @($ArtifactPath)
    $artifactsProvided = $artifactArguments.Count -gt 0
    $emptyEvidence = [ordered]@{
        source = $null
        artifacts = @()
        scope = 'repository-root'
        freshnessReference = $Since.ToString('o')
    }
    if (-not $sourceProvided -and -not $artifactsProvided) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'explicit SBOM legal inputs are required'; blockerCodes = @('SBOM_INPUTS_NOT_PROVIDED'); inputs = $emptyEvidence }
    }
    if ($sourceProvided -xor $artifactsProvided) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'corresponding source and artifact inputs must be supplied together'; blockerCodes = @('SBOM_INPUTS_PARTIAL'); inputs = $emptyEvidence }
    }

    $inputArguments = [System.Collections.Generic.List[object]]::new()
    $inputArguments.Add([ordered]@{ kind = 'corresponding-source'; value = [string]$CorrespondingSourcePath })
    foreach ($artifact in $artifactArguments) {
        $inputArguments.Add([ordered]@{ kind = 'artifact'; value = [string]$artifact })
    }
    $evidence = [System.Collections.Generic.List[object]]::new()
    $blockerCodes = [System.Collections.Generic.List[string]]::new()
    $repositoryPrefix = $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($input in $inputArguments) {
        $value = [string]$input.value
        if (-not [System.IO.Path]::IsPathRooted($value)) {
            $blockerCodes.Add('SBOM_INPUT_NOT_ABSOLUTE')
            continue
        }
        try {
            $resolved = (Resolve-Path -LiteralPath $value -ErrorAction Stop).Path
            $full = [System.IO.Path]::GetFullPath($resolved)
            $inScope = $full.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or $full.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)
            if (-not $inScope) {
                $blockerCodes.Add('SBOM_INPUT_OUT_OF_SCOPE')
                continue
            }
            $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
            $latestWrite = $item.LastWriteTimeUtc
            $entryCount = $null
            $sha256 = $null
            if ($item.PSIsContainer) {
                $children = @(Get-ChildItem -LiteralPath $full -Recurse -File -Force -ErrorAction Stop)
                $entryCount = [int]$children.Count
                if ($children.Count -eq 0) { $blockerCodes.Add('SBOM_INPUT_EMPTY') }
                if ($children.Count -gt 0) {
                    $latestWrite = ($children | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
                }
            } else {
                $sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            }
            $fresh = Test-FreshTimestamp -LastWriteTimeUtc $latestWrite -Since $Since
            if (-not $fresh) { $blockerCodes.Add('SBOM_INPUT_STALE') }
            $evidence.Add([ordered]@{
                    kind = [string]$input.kind
                    path = Get-SafePath -Path $full
                    type = if ($item.PSIsContainer) { 'directory' } else { 'file' }
                    exists = $true
                    freshThisRun = $fresh
                    sha256 = $sha256
                    entryCount = $entryCount
                    mtime = $item.LastWriteTimeUtc.ToString('o')
                })
        } catch {
            $blockerCodes.Add('SBOM_INPUT_MISSING')
        }
    }
    $uniqueCodes = @($blockerCodes | Sort-Object -Unique)
    $sourceEvidence = @($evidence | Where-Object { $_.kind -eq 'corresponding-source' }) | Select-Object -First 1
    $artifactEvidence = @($evidence | Where-Object { $_.kind -eq 'artifact' })
    $safeInputs = [ordered]@{
        source = $sourceEvidence
        artifacts = @($artifactEvidence)
        scope = 'repository-root'
        freshnessReference = $Since.ToString('o')
    }
    return [ordered]@{
        passed = $uniqueCodes.Count -eq 0 -and $evidence.Count -eq $inputArguments.Count
        status = if ($uniqueCodes.Count -eq 0 -and $evidence.Count -eq $inputArguments.Count) { 'passed' } else { 'blocked' }
        blocker = if ($uniqueCodes.Count -eq 0) { $null } else { 'one or more explicit SBOM legal inputs failed validation' }
        blockerCodes = $uniqueCodes
        inputs = $safeInputs
    }
}

# Validates the exact BOM emitted by the pinned CycloneDX producer, including freshness and a
# content hash. The later license report must compare against this hash instead of trusting a
# pre-existing target file with the same name.
function Get-SbomBomFreshnessEvidence {
    $path = Join-Path $evidenceRoot 'sbom\ja-app-server-bom.json'
    $producer = $results | Where-Object name -eq 'sbom-bom' | Select-Object -First 1
    if ($null -eq $producer -or -not $producer.executed -or -not $producer.passed) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'CycloneDX BOM producer did not complete'; path = 'sbom/ja-app-server-bom.json'; sha256 = $null; bytes = $null }
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'fresh CycloneDX BOM is missing'; path = 'sbom/ja-app-server-bom.json'; sha256 = $null; bytes = $null }
    }
    try {
        $producerStartedAt = [DateTimeOffset]::Parse([string]$producer.startedAt)
        $file = Get-Item -LiteralPath $path
        if (-not (Test-FreshTimestamp -LastWriteTimeUtc $file.LastWriteTimeUtc -Since $producerStartedAt)) {
            return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'CycloneDX BOM is stale or future-dated'; path = 'sbom/ja-app-server-bom.json'; sha256 = $null; bytes = [int64]$file.Length }
        }
        $bom = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ([string]$bom.bomFormat -ne 'CycloneDX') {
            return [ordered]@{ passed = $false; status = 'failed'; blocker = 'CycloneDX BOM has an invalid format'; path = 'sbom/ja-app-server-bom.json'; sha256 = $null; bytes = [int64]$file.Length }
        }
        return [ordered]@{ passed = $true; status = 'passed'; blocker = $null; path = 'sbom/ja-app-server-bom.json'; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = [int64]$file.Length; bomFormat = [string]$bom.bomFormat; specVersion = [string]$bom.specVersion }
    } catch {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'CycloneDX BOM is missing or malformed'; path = 'sbom/ja-app-server-bom.json'; sha256 = $null; bytes = $null }
    }
}

# Reads the mature SBOM generator's bounded report and hashes every fresh JSON artifact. Candidate
# promotion is intentionally absent: verification can report legal blockers but cannot approve them.
function Get-SbomLicenseEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $root = Join-Path $evidenceRoot 'sbom'
    $inputGate = $results | Where-Object name -eq 'sbom-inputs' | Select-Object -First 1
    if ($null -eq $inputGate -or -not $inputGate.executed -or -not $inputGate.passed) {
        return [ordered]@{ status = 'blocked'; blocker = 'SBOM legal input gate did not pass'; blockerCodes = @('SBOM_INPUT_GATE_BLOCKED'); promotedCandidates = $false; artifacts = @() }
    }
    $bomGate = $results | Where-Object name -eq 'sbom-bom-freshness' | Select-Object -First 1
    if ($null -eq $bomGate -or -not $bomGate.executed -or -not $bomGate.passed) {
        return [ordered]@{ status = 'blocked'; blocker = 'fresh CycloneDX BOM gate did not pass'; promotedCandidates = $false; artifacts = @() }
    }
    $bomPath = Join-Path $root 'ja-app-server-bom.json'
    $bomHash = (Get-FileHash -LiteralPath $bomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($bomHash -ne [string]$bomGate.details.sha256) {
        return [ordered]@{ status = 'blocked'; blocker = 'CycloneDX BOM changed after freshness validation'; promotedCandidates = $false; artifacts = @() }
    }
    $reportPath = Join-Path $root 'dependency-license-report.json'
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        return [ordered]@{ status = 'blocked'; blocker = 'fresh dependency and license report is missing'; promotedCandidates = $false; artifacts = @() }
    }
    $reportFile = Get-Item -LiteralPath $reportPath
    if (-not (Test-FreshTimestamp -LastWriteTimeUtc $reportFile.LastWriteTimeUtc -Since $Since)) {
        return [ordered]@{ status = 'blocked'; blocker = 'dependency and license report is stale'; promotedCandidates = $false; artifacts = @() }
    }
    try {
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        $artifacts = @(Get-ChildItem -LiteralPath $root -File -Filter '*.json' | Where-Object { Test-FreshTimestamp -LastWriteTimeUtc $_.LastWriteTimeUtc -Since $Since } | Sort-Object Name | ForEach-Object {
            [ordered]@{ name = $_.Name; bytes = [int64]$_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
        $codes = @($report.blockers | ForEach-Object { [string]$_.code } | Where-Object { $_ } | Sort-Object -Unique)
        $gate = $results | Where-Object name -eq 'sbom-license' | Select-Object -First 1
        $passed = $null -ne $gate -and $gate.executed -and $gate.passed -and $report.status -eq 'complete' -and $codes.Count -eq 0
        return [ordered]@{ status = if ($passed) { 'passed' } elseif ($null -eq $gate -or -not $gate.executed) { 'blocked' } else { 'failed' }; blocker = if ($passed) { $null } elseif ($codes.Count -ne 0) { 'dependency or license review has unresolved blockers' } else { 'SBOM and license gate did not complete successfully' }; blockerCodes = $codes; bom = [ordered]@{ path = 'ja-app-server-bom.json'; sha256 = $bomHash; bytes = [int64](Get-Item -LiteralPath $bomPath).Length }; promotedCandidates = $false; artifacts = $artifacts }
    } catch {
        return [ordered]@{ status = 'blocked'; blocker = 'dependency and license report is malformed'; promotedCandidates = $false; artifacts = @() }
    }
}

# Parses the architecture reporter without copying source excerpts into the consolidated summary.
function Get-JavaArchitectureEvidence {
    $path = Join-Path $evidenceRoot 'java-architecture.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return [ordered]@{ status = 'blocked'; blocker = 'fresh Java architecture report is missing' } }
    if (-not (Test-FreshTimestamp -LastWriteTimeUtc (Get-Item -LiteralPath $path).LastWriteTimeUtc -Since $runStartedAt)) {
        return [ordered]@{ status = 'blocked'; blocker = 'Java architecture report is stale or future-dated' }
    }
    try {
        $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $gate = $results | Where-Object name -eq 'java-architecture' | Select-Object -First 1
        $passed = $document.passed -and $null -ne $gate -and $gate.executed -and $gate.passed
        return [ordered]@{ status = if ($passed) { 'passed' } elseif ($null -eq $gate -or -not $gate.executed) { 'blocked' } else { 'failed' }; blocker = if ($passed) { $null } elseif (-not $document.passed) { 'Java architecture report contains violations' } else { 'Java architecture gate did not complete successfully' }; productionFiles = [int]$document.productionFiles; violationCount = [int]$document.violationCount; observationCount = if ($null -ne $document.PSObject.Properties['observationCount']) { [int]$document.observationCount } else { 0 }; report = 'java-architecture.json' }
    } catch {
        return [ordered]@{ status = 'blocked'; blocker = 'Java architecture report is malformed' }
    }
}

# Adds explicit skipped/blocked booleans to every gate after execution. Status remains the primary
# verdict, while these fields make prerequisite blocks distinguishable from opt-out skips.
function Add-ExplicitGateState {
    foreach ($result in $results) {
        $result['skipped'] = $result.status -eq 'skipped'
        $result['blocked'] = $result.status -eq 'blocked' -or ($result.status -eq 'skipped' -and $result.requested)
    }
}

# Combines author, secret, process-residual and artifact-size findings into one auditable static
# gate. Each sub-result remains visible, so a single aggregate failure cannot hide its cause.
function Get-RepositoryStaticGateEvidence {
    param([Parameter(Mandatory)][object[]]$Artifacts)

    $authorFindings = [System.Collections.Generic.List[object]]::new()
    $changed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($line in @(git -C $repositoryRoot diff --name-only --diff-filter=AM 2>$null)) {
        if ($line -and -not (Test-GeneratedRepositoryPath -RelativePath ([string]$line))) {
            $changed.Add((Join-Path $repositoryRoot ([string]$line))) | Out-Null
        }
    }
    foreach ($line in @(git -C $repositoryRoot ls-files --others --exclude-standard 2>$null)) {
        if ($line -and -not (Test-GeneratedRepositoryPath -RelativePath ([string]$line))) {
            $changed.Add((Join-Path $repositoryRoot ([string]$line))) | Out-Null
        }
    }
    foreach ($path in $changed) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        if ([System.IO.Path]::GetExtension($path).ToLowerInvariant() -notin @('.ps1', '.psm1', '.py', '.mjs', '.js', '.ts', '.tsx', '.rs', '.java')) { continue }
        $head = (Get-Content -LiteralPath $path -TotalCount 20 -ErrorAction SilentlyContinue) -join "`n"
        if ($head -notmatch '@author\s+kongweiguang') { $authorFindings.Add([ordered]@{ path = Get-SafePath -Path $path; rule = 'author' }) }
    }
    $secret = Get-SecretGateEvidence
    $residual = Get-ResidualProcessEvidence
    $size = Get-SizeGateEvidence -Artifacts $Artifacts
    $findings = @($authorFindings) + @($secret.findings) + @($size.findings)
    if (-not $residual.passed) { $findings += [ordered]@{ rule = 'residual-process'; detail = $residual.blocker } }
    return [ordered]@{ passed = $authorFindings.Count -eq 0 -and $secret.passed -and $residual.passed -and $size.passed; author = [ordered]@{ passed = $authorFindings.Count -eq 0; findingCount = $authorFindings.Count; findings = @($authorFindings) }; secret = $secret; residual = $residual; size = $size; findings = @($findings) }
}

# Executes an internal evidence gate and converts thrown diagnostics to stable blocker codes; no
# internal failure can bypass the final summary writer.
function Invoke-InternalGate {
    param([Parameter(Mandatory)]$Step, [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Artifacts)

    $startedAt = [DateTimeOffset]::Now
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $details = switch ($Step.name) {
            'evidence-freshness' { Get-EvidenceFreshnessEvidence }
            'java-25-preflight' { Get-Java25Evidence }
            'native-no-fallback-policy' { Get-NativePolicyEvidence }
            'native-artifact-freshness' { Get-NativeArtifactFreshnessEvidence }
            'provider-preflight' { Get-ProviderPreflightEvidence }
            'sbom-bom-freshness' { Get-SbomBomFreshnessEvidence }
            'sbom-inputs' { Get-SbomInputEvidence -Since $runStartedAt }
            'repository-static-gates' { Get-RepositoryStaticGateEvidence -Artifacts @(Get-ArtifactEvidence -Since $runStartedAt) }
            default { throw "unknown internal gate: $($Step.name)" }
        }
        $watch.Stop()
        $blocker = if ($details -is [System.Collections.IDictionary] -and $details.Contains('blocker')) {
            $details['blocker']
        } elseif ($null -ne $details.PSObject.Properties['blocker']) {
            $details.PSObject.Properties['blocker'].Value
        } else { $null }
        $blockedPrecondition = -not $details.passed -and $Step.name -in @('java-25-preflight', 'provider-preflight', 'native-artifact-freshness', 'sbom-bom-freshness', 'sbom-inputs')
        return [ordered]@{ name = $Step.name; status = if ($details.passed) { 'passed' } elseif ($blockedPrecondition) { 'blocked' } else { 'failed' }; requested = $Step.requested; optional = $Step.optional; executed = $true; passed = [bool]$details.passed; blocker = $blocker; failureType = $null; exitCode = if ($details.passed) { 0 } else { 1 }; dependsOn = @($Step.dependsOn); startedAt = $startedAt.ToString('o'); durationMs = $watch.ElapsedMilliseconds; stdout = $null; stderr = $null; details = $details }
    } catch {
        $watch.Stop()
        return [ordered]@{ name = $Step.name; status = 'failed'; requested = $Step.requested; optional = $Step.optional; executed = $true; passed = $false; blocker = 'internal-gate-error'; failureType = $_.Exception.GetType().Name; exitCode = 1; dependsOn = @($Step.dependsOn); startedAt = $startedAt.ToString('o'); durationMs = $watch.ElapsedMilliseconds; stdout = $null; stderr = $null }
    }
}

# Produces a dependency-aware skipped result so every requested and optional gate is represented
# even when an earlier build failure prevents its safe execution.
function New-SkippedResult {
    param([Parameter(Mandatory)]$Step, [Parameter(Mandatory)][string]$Blocker)

    return [ordered]@{ name = $Step.name; status = 'skipped'; requested = $Step.requested; optional = $Step.optional; executed = $false; passed = $false; blocker = $Blocker; failureType = $null; exitCode = $null; dependsOn = @($Step.dependsOn); startedAt = $null; durationMs = 0; stdout = $null; stderr = $null }
}

# Projects complete gate state into each product capability instead of reducing evidence to a
# status string. Reviewers can therefore distinguish opt-out, dependency block and real failure.
function Get-CapabilityGateState {
    param([Parameter(Mandatory)][string[]]$Names)

    return @($Names | ForEach-Object {
        $name = $_
        $result = $results | Where-Object name -eq $name | Select-Object -First 1
        if ($null -eq $result) {
            [ordered]@{ gate = $name; status = 'blocked'; requested = $true; executed = $false; blocked = $true; passed = $false; dependsOn = @(); blocker = 'gate result is missing' }
        } else {
            [ordered]@{ gate = $name; status = $result.status; requested = [bool]$result.requested; executed = [bool]$result.executed; blocked = [bool]$result.blocked; passed = $result.status -eq 'passed'; dependsOn = @($result.dependsOn); blocker = $result.blocker }
        }
    })
}

# Converts artifact and tool outputs into a compact summary only after all steps have run; no
# hardcoded test count is used, and current corpus/artifact identities remain reviewable.
function New-VerificationSummary {
    param([Parameter(Mandatory)][DateTimeOffset]$StartedAt)

    $artifacts = @(Get-ArtifactEvidence -Since $StartedAt)
    Add-ExplicitGateState
    $requested = @($results | Where-Object requested)
    $failed = @($requested | Where-Object { -not $_.passed })
    $contractEvidence = Get-ContractTestEvidence
    $mavenEvidence = Get-MavenTestEvidence -Since $StartedAt
    $rustEvidence = @(Get-RustTestEvidence)
    $vitestEvidence = Get-VitestTestEvidence -Since $StartedAt
    $nativeSmokeEvidence = Get-NativeSmokeIdentityEvidence -Since $StartedAt
    $architectureEvidence = Get-JavaArchitectureEvidence
    $javaEvidence = Get-Java25Evidence
    $sbomInputEvidence = Get-SbomInputEvidence -Since $StartedAt
    $sbomEvidence = Get-SbomLicenseEvidence -Since $StartedAt
    $derivedEvidencePassed = $contractEvidence.status -eq 'passed' -and $mavenEvidence.status -eq 'passed' -and $vitestEvidence.status -eq 'passed' -and $architectureEvidence.status -eq 'passed' -and $sbomEvidence.status -eq 'passed' -and $nativeSmokeEvidence.status -in @('passed', 'skipped') -and @($rustEvidence | Where-Object { $_.status -ne 'passed' }).Count -eq 0 -and @($artifacts | Where-Object { $_.required -and $_.status -ne 'passed' }).Count -eq 0
    return [ordered]@{
        schemaVersion = 2
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        startedAt = $StartedAt.ToString('o')
        repository = 'ja'
        repositoryRoot = '<repo>'
        evidenceDirectory = Get-SafePath -Path $evidenceRoot
        evidenceDirectoryPreexisted = $evidencePreexisted
        evidenceDirectoryWasNonEmpty = $evidenceWasNonEmpty
        requestedSteps = $requested.Count
        executedSteps = @($results | Where-Object executed).Count
        skippedSteps = @($results | Where-Object { $_.status -eq 'skipped' }).Count
        blockedSteps = @($results | Where-Object blocked).Count
        failedSteps = $failed.Count
        passed = $failed.Count -eq 0 -and @($requested | Where-Object passed).Count -eq $requested.Count -and $derivedEvidencePassed
        derivedEvidencePassed = $derivedEvidencePassed
        results = @($results)
        contract = $contractEvidence
        maven = $mavenEvidence
        java = $javaEvidence
        rust = $rustEvidence
        vitest = $vitestEvidence
        artifacts = $artifacts
        nativeSmoke = $nativeSmokeEvidence
        corpus = Get-ContractCorpusEvidence
        architecture = $architectureEvidence
        sbomInputs = $sbomInputEvidence
        sbomLicense = $sbomEvidence
        capabilityStatus = [ordered]@{
            approvalDeadlock = Get-CapabilityGateState -Names @('java-jvm', 'windows-webview2')
            cancellation = Get-CapabilityGateState -Names @('java-jvm', 'java-native-smoke', 'windows-webview2')
            singleTerminal = Get-CapabilityGateState -Names @('java-jvm', 'java-native-smoke')
            sqliteRecovery = Get-CapabilityGateState -Names @('java-jvm', 'java-native-smoke')
            httpSse = Get-CapabilityGateState -Names @('java-jvm', 'real-provider-openai', 'real-provider-anthropic')
            shutdown = Get-CapabilityGateState -Names @('java-jvm', 'kernel-loop-smoke', 'java-native-smoke', 'windows-webview2')
            native = Get-CapabilityGateState -Names @('native-no-fallback-policy', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke')
            desktop = Get-CapabilityGateState -Names @('windows-webview2')
            providers = Get-CapabilityGateState -Names @('provider-preflight', 'real-provider-openai', 'real-provider-anthropic')
            runtimeSoak120Minutes = Get-CapabilityGateState -Names @('runtime-soak')
        }
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false; freshnessReference = $StartedAt.ToString('o') }
    }
}

# Writes a summary even when setup or a gate fails; the writer itself avoids including exception
# messages because PowerShell tool errors can contain paths, command lines or credential echoes.
function Write-VerificationSummary {
    param([Parameter(Mandatory)]$Summary)

    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $summaryPath = Join-Path $evidenceRoot 'summary.json'
    [System.IO.File]::WriteAllText($summaryPath, ($Summary | ConvertTo-Json -Depth 16) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# Creates the complete phase inventory up front so ListOnly and failure summaries show optional
# provider/desktop/native requirements explicitly instead of omitting a non-requested gate.
function Initialize-VerificationSteps {
    Add-VerificationStep -Name 'evidence-freshness' -Requested $true -Kind internal
    Add-VerificationStep -Name 'java-25-preflight' -Requested $true -Kind internal
    Add-VerificationStep -Name 'java-architecture' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/check-java-architecture.ps1', '-OutputPath', (Join-Path $evidenceRoot 'java-architecture.json'))
    Add-VerificationStep -Name 'contract' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'tests/contract/run.ps1') -Environment $commonEnvironment
    Add-VerificationStep -Name 'java-jvm' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'app-server/pom.xml', 'test') -Environment $commonEnvironment
    Add-VerificationStep -Name 'java-artifact-package' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/package-java-app-server.ps1', '-RepositoryRoot', $repositoryRoot, '-OutputDirectory', $javaArtifactDirectory) -Environment $commonEnvironment -DependsOn @('java-jvm')
    Add-VerificationStep -Name 'kernel-loop-smoke' -Requested $true -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/kernel-loop-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_KERNEL_LOOP_CAPTURE = '' }) -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'rust-fmt' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('fmt', '--all', '--', '--check') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-clippy' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('clippy', '-p', 'ja', '-p', 'ja-runtime', '--all-targets', '--locked', '--', '-D', 'warnings') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-runtime-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja-runtime', '--all-targets', '--locked') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-tauri-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja', '--lib', '--locked') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-host-integration' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja', '--test', 'unit', '--locked') -Environment $commonEnvironment -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'typescript-typecheck' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('typecheck')
    Add-VerificationStep -Name 'typescript-lint' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('lint')
    Add-VerificationStep -Name 'typescript-tests' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('test', '--reporter=json', '--outputFile', (Join-Path $evidenceRoot 'vitest.json'))
    Add-VerificationStep -Name 'desktop-build' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('build') -DependsOn @('typescript-typecheck')
    Add-VerificationStep -Name 'workspace-architecture' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('check:architecture')
    Add-VerificationStep -Name 'maven-dependency-tree' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'app-server/pom.xml', 'dependency:tree', '-Dverbose') -Environment $commonEnvironment
    Add-VerificationStep -Name 'maven-dependency-convergence' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'app-server/pom.xml', 'enforcer:enforce') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-dependency-tree' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('tree', '--workspace', '--locked', '--all-features', '--duplicates') -Environment $commonEnvironment
    Add-VerificationStep -Name 'node-dependency-tree' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('list', '--depth', '1', '--json')
    Add-VerificationStep -Name 'sbom-bom' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'app-server/pom.xml', 'org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom', '-DskipTests', '-DoutputFormat=json', '-DoutputName=ja-app-server-bom', "-DoutputDirectory=$(Join-Path $evidenceRoot 'sbom')") -Environment $commonEnvironment
    Add-VerificationStep -Name 'sbom-bom-freshness' -Requested $true -Kind internal -DependsOn @('sbom-bom')
    Add-VerificationStep -Name 'sbom-inputs' -Requested $true -Kind internal -DependsOn @('sbom-bom-freshness') -RequiredInputs @('CorrespondingSourcePath: absolute, existing, fresh, repository-scoped', 'ArtifactPath: one or more absolute, existing, fresh, repository-scoped paths')
    $sbomLicenseArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @('-NoProfile', '-File', 'scripts/release/sbom/generate.ps1', '-RepositoryRoot', $repositoryRoot, '-OutputDirectory', (Join-Path $evidenceRoot 'sbom'), '-MavenBomPath', (Join-Path $evidenceRoot 'sbom\ja-app-server-bom.json'))) {
        $sbomLicenseArguments.Add([string]$argument)
    }
    if (-not [string]::IsNullOrWhiteSpace($CorrespondingSourcePath)) {
        $sbomLicenseArguments.Add('-CorrespondingSourcePath')
        $sbomLicenseArguments.Add($CorrespondingSourcePath)
    }
    if (@($ArtifactPath).Count -gt 0) {
        $sbomLicenseArguments.Add('-ArtifactPath')
        foreach ($artifact in @($ArtifactPath)) {
            $sbomLicenseArguments.Add([string]$artifact)
        }
    }
    $sbomLicenseArguments.Add('-FailOnBlocker')
    Add-VerificationStep -Name 'sbom-license' -Requested $true -Kind command -Command 'pwsh' -Arguments @($sbomLicenseArguments) -PersistOutput $false -DependsOn @('sbom-bom-freshness', 'sbom-inputs') -RequiredInputs @('CorrespondingSourcePath', 'ArtifactPath')
    Add-VerificationStep -Name 'git-diff-check' -Requested $true -Kind command -Command 'git.exe' -Arguments @('diff', '--check', '--')
    Add-VerificationStep -Name 'native-no-fallback-policy' -Requested $nativeRequested -Kind internal -Optional $true
    # Native Image is the only clean build in this runner.  Keep it behind every already-produced
    # contract/JVM/Host/smoke and SBOM/license stop-ship so a failed prerequisite cannot consume a
    # one-shot Windows build or leave a misleading executable for later gates.
    Add-VerificationStep -Name 'java-native-build' -Requested $nativeRequested -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'app-server/pom.xml', '-Pnative', '-DskipTests', 'clean', 'package') -Environment $commonEnvironment -DependsOn @('java-25-preflight', 'java-architecture', 'contract', 'java-jvm', 'rust-host-integration', 'kernel-loop-smoke', 'sbom-bom-freshness', 'sbom-inputs', 'sbom-license', 'native-no-fallback-policy') -Optional $true
    Add-VerificationStep -Name 'native-artifact-freshness' -Requested $nativeRequested -Kind internal -DependsOn @('java-native-build') -Optional $true
    Add-VerificationStep -Name 'java-native-smoke' -Requested $nativeRequested -Kind command -Command 'python.exe' -Arguments @('scripts/native/run-sidecar-smoke.py', '--executable', (Join-Path $repositoryRoot 'app-server/target/ja-app-server.exe'), '--data-dir', (Join-Path $evidenceRoot 'native-data'), '--output', (Join-Path $evidenceRoot 'native-smoke.json')) -DependsOn @('native-artifact-freshness') -Optional $true
    Add-VerificationStep -Name 'runtime-soak' -Requested ([bool]$IncludeSoak) -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/run-runtime-soak.ps1', '-EvidenceDirectory', (Join-Path $evidenceRoot 'runtime-soak'), '-Executable', (Join-Path $repositoryRoot 'app-server/target/ja-app-server.exe'), '-DurationMinutes', [string]$SoakDurationMinutes) -DependsOn @('java-native-smoke') -Optional $true
    Add-VerificationStep -Name 'windows-webview2' -Requested ([bool]$IncludeDesktop) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/windows-desktop-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_E2E_REAL_PROVIDER = '0'; JA_E2E_REAL_PROVIDER_OPENAI_API_KEY = ''; JA_E2E_REAL_PROVIDER_ANTHROPIC_API_KEY = ''; JA_E2E_KEEP_TEMP = '0' }) -DependsOn @('java-artifact-package', 'kernel-loop-smoke', 'rust-tauri-tests', 'rust-host-integration', 'typescript-tests', 'desktop-build') -PersistOutput $false -Optional $true
    Add-VerificationStep -Name 'provider-preflight' -Requested ([bool]$IncludeRealProvider) -Kind internal -Optional $true
    Add-VerificationStep -Name 'real-provider-openai' -Requested ([bool]$IncludeRealProvider) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/real-provider-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_REAL_PROVIDER_PROVIDER = 'openai' }) -DependsOn @('provider-preflight', 'java-artifact-package', 'kernel-loop-smoke') -PersistOutput $false -Optional $true
    Add-VerificationStep -Name 'real-provider-anthropic' -Requested ([bool]$IncludeRealProvider) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/real-provider-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_REAL_PROVIDER_PROVIDER = 'anthropic' }) -DependsOn @('provider-preflight', 'java-artifact-package', 'kernel-loop-smoke') -PersistOutput $false -Optional $true
    Add-VerificationStep -Name 'repository-static-gates' -Requested $true -Kind internal
}

Initialize-Java25Environment

Initialize-VerificationSteps

if ($ListOnly) {
    @($steps | ForEach-Object {
        [ordered]@{ name = $_.name; requested = $_.requested; optional = $_.optional; kind = $_.kind; command = if ($_.command) { $_.command } else { $null }; arguments = @(Get-DisplayArguments -Arguments @($_.arguments)); dependsOn = @($_.dependsOn); requiredInputs = @($_.requiredInputs); persistOutput = $_.persistOutput }
    }) | ConvertTo-Json -Depth 8
    exit 0
}

# A non-empty evidence directory is immutable input, not a place to overwrite with a blocker.
# Emit a complete gate inventory to stdout and leave every pre-existing byte untouched.
if ($evidenceWasNonEmpty) {
    foreach ($step in $steps) {
        if ($step.name -eq 'evidence-freshness') {
            $results.Add([ordered]@{ name = $step.name; status = 'blocked'; requested = $true; optional = $false; executed = $true; passed = $false; blocker = 'evidence directory is not fresh; choose a new directory'; failureType = $null; exitCode = 1; dependsOn = @(); startedAt = $runStartedAt.ToString('o'); durationMs = 0; stdout = $null; stderr = $null })
        } else {
            $results.Add((New-SkippedResult -Step $step -Blocker $(if ($step.requested) { 'evidence freshness gate failed' } else { 'gate was not requested' })))
        }
    }
    Add-ExplicitGateState
    $staleSummary = [ordered]@{
        schemaVersion = 2
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        startedAt = $runStartedAt.ToString('o')
        repository = 'ja'
        repositoryRoot = '<repo>'
        evidenceDirectory = Get-SafePath -Path $evidenceRoot
        evidenceDirectoryPreexisted = $true
        evidenceDirectoryWasNonEmpty = $true
        requestedSteps = @($results | Where-Object requested).Count
        executedSteps = @($results | Where-Object executed).Count
        skippedSteps = @($results | Where-Object skipped).Count
        blockedSteps = @($results | Where-Object blocked).Count
        failedSteps = @($results | Where-Object { $_.requested -and -not $_.passed }).Count
        passed = $false
        results = @($results)
        blocker = 'evidence-directory-not-fresh'
        evidenceWritten = $false
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false }
    }
    $staleSummary | ConvertTo-Json -Depth 16
    exit 1
}

if (-not (New-EvidenceRunClaim)) {
    foreach ($step in $steps) {
        if ($step.name -eq 'evidence-freshness') {
            $results.Add([ordered]@{ name = $step.name; status = 'blocked'; requested = $true; optional = $false; executed = $true; passed = $false; blocker = 'evidence directory is already claimed by another run'; failureType = $null; exitCode = 1; dependsOn = @(); startedAt = $runStartedAt.ToString('o'); durationMs = 0; stdout = $null; stderr = $null })
        } else {
            $results.Add((New-SkippedResult -Step $step -Blocker $(if ($step.requested) { 'evidence directory claim failed' } else { 'gate was not requested' })))
        }
    }
    Add-ExplicitGateState
    [ordered]@{
        schemaVersion = 2; generatedAt = [DateTimeOffset]::Now.ToString('o'); startedAt = $runStartedAt.ToString('o')
        repository = 'ja'; repositoryRoot = '<repo>'; evidenceDirectory = Get-SafePath -Path $evidenceRoot
        requestedSteps = @($results | Where-Object requested).Count; executedSteps = @($results | Where-Object executed).Count
        skippedSteps = @($results | Where-Object skipped).Count; blockedSteps = @($results | Where-Object blocked).Count
        failedSteps = @($results | Where-Object { $_.requested -and -not $_.passed }).Count; passed = $false
        results = @($results); blocker = 'evidence-directory-claim-failed'; evidenceWritten = $false
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false }
    } | ConvertTo-Json -Depth 16
    exit 1
}

try {
    [System.IO.Directory]::CreateDirectory($cargoTarget) | Out-Null
    $evidenceBlocked = $false
    foreach ($step in $steps) {
        if (-not $step.requested) {
            $results.Add((New-SkippedResult -Step $step -Blocker 'gate was not requested'))
            continue
        }
        if ($evidenceBlocked) {
            $results.Add((New-SkippedResult -Step $step -Blocker 'evidence freshness gate failed'))
            continue
        }
        if ($javaHomeIssue -and $step.name -in @('java-jvm', 'java-artifact-package', 'maven-dependency-tree', 'maven-dependency-convergence', 'kernel-loop-smoke', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke', 'runtime-soak', 'windows-webview2', 'real-provider-openai', 'real-provider-anthropic')) {
            $results.Add((New-SkippedResult -Step $step -Blocker "JavaHome preflight failed: $javaHomeIssue"))
            continue
        }
        $dependencyResults = @($step.dependsOn | ForEach-Object { $results | Where-Object name -eq $_ | Select-Object -First 1 })
        $failedDependency = $dependencyResults | Where-Object { -not $_.passed } | Select-Object -First 1
        if ($null -ne $failedDependency) {
            $results.Add((New-SkippedResult -Step $step -Blocker "dependency failed or was blocked: $($failedDependency.name)"))
            continue
        }
        if ($step.kind -eq 'internal') {
            $internalResult = Invoke-InternalGate -Step $step -Artifacts @()
            $results.Add($internalResult)
            if ($step.name -eq 'evidence-freshness' -and -not $internalResult.passed) { $evidenceBlocked = $true }
        } else {
            if ($step.name -in @('java-native-smoke', 'runtime-soak')) {
                $nativeEvidence = Get-NativeArtifactFreshnessEvidence
                if (-not $nativeEvidence.passed) {
                    $results.Add((New-SkippedResult -Step $step -Blocker 'fresh native artifact identity is unavailable'))
                    continue
                }
                if ($step.name -eq 'java-native-smoke') {
                    $step.arguments = @($step.arguments) + @('--expected-sha256', [string]$nativeEvidence.artifact.sha256, '--expected-size', [string]$nativeEvidence.artifact.actualBytes, '--expected-mtime-ns', [string]$nativeEvidence.artifact.mtimeNs)
                } else {
                    $step.arguments = @($step.arguments) + @('-ExpectedSha256', [string]$nativeEvidence.artifact.sha256, '-ExpectedSizeBytes', [string]$nativeEvidence.artifact.actualBytes, '-ExpectedMtimeNs', [string]$nativeEvidence.artifact.mtimeNs)
                }
            }
            $commandResult = Invoke-CommandGate -Step $step
            if ($step.name -eq 'java-jvm') {
                # Snapshot before the next dependency can run: packaging intentionally uses clean
                # isolated output and may remove the shared target reports after this point.
                $snapshotResult = if ($commandResult.passed) {
                    Save-MavenSurefireSnapshot -GateResult $commandResult
                } else {
                    [ordered]@{
                        status = 'not-run'
                        blocker = 'Maven test gate did not pass; snapshot was not attempted'
                        expectedCount = 0
                        copiedCount = 0
                        sourceReportCount = 0
                        snapshotDirectory = $mavenSurefireSnapshotDirectoryName
                    }
                }
                $script:mavenSurefireSnapshot = $snapshotResult
                $commandResult['mavenSurefireSnapshot'] = $snapshotResult
            }
            $results.Add($commandResult)
        }
    }
} catch {
    # Preserve a stable blocker and continue to the summary writer; exception text is deliberately
    # redacted before retention so command-envelope regressions remain attributable without
    # persisting host paths, child output, or credentials.
    $results.Add([ordered]@{ name = 'verification-runner'; status = 'failed'; requested = $true; executed = $false; passed = $false; blocker = 'runner-exception'; failureType = $_.Exception.GetType().Name; failureCode = ConvertTo-SafeDiagnosticText -Value $_.FullyQualifiedErrorId; exitCode = 1; startedAt = $null; durationMs = 0; stdout = $null; stderr = $null })
    $results[$results.Count - 1]['failureSite'] = ConvertTo-SafeDiagnosticText -Value $_.InvocationInfo.PositionMessage
    $results[$results.Count - 1]['failureMessage'] = ConvertTo-SafeDiagnosticText -Value $_.Exception.Message
}

$summary = $null
try {
    $summary = New-VerificationSummary -StartedAt $runStartedAt
} catch {
    # Keep a machine-readable failure when summary enrichment fails; diagnostics are redacted before
    # retention so the runner can be repaired without persisting host paths, commands, or secrets.
    $summary = [ordered]@{
        schemaVersion = 2
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        startedAt = $runStartedAt.ToString('o')
        repository = 'ja'
        repositoryRoot = '<repo>'
        evidenceDirectory = Get-SafePath -Path $evidenceRoot
        passed = $false
        requestedSteps = @($results | Where-Object requested).Count
        executedSteps = @($results | Where-Object executed).Count
        skippedSteps = @($results | Where-Object { $_.status -eq 'skipped' }).Count
        failedSteps = 1
        results = @($results)
        blocker = 'summary-generation-failed'
        failureType = $_.Exception.GetType().Name
        failureCode = ConvertTo-SafeDiagnosticText -Value $_.FullyQualifiedErrorId
        failureMessage = ConvertTo-SafeDiagnosticText -Value $_.Exception.Message
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false }
    }
}
Write-VerificationSummary -Summary $summary
$summary | ConvertTo-Json -Depth 16
if (-not $summary.passed) { exit 1 }
