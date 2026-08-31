# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$EvidenceDirectory = '.tmp/verification/runtime-soak',
    [string]$Executable = 'app-server/target/ja-app-server.exe',
    [string]$ExpectedSha256 = '',
    [long]$ExpectedSizeBytes = 0,
    [long]$ExpectedMtimeNs = 0,
    [int]$DurationMinutes = 120,
    [int]$IntervalSeconds = 30,
    [switch]$KeepData,
    [switch]$ValidateOnly,
    [int]$MaxThreadGrowth = 128,
    [int]$MaxHandleGrowth = 4096,
    [long]$MaxWorkingSetGrowthBytes = 536870912,
    [long]$MaxDatabaseGrowthBytes = 52428800
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidenceRoot = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) { $EvidenceDirectory } else { Join-Path $repositoryRoot $EvidenceDirectory }))
$executablePath = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($Executable)) { $Executable } else { Join-Path $repositoryRoot $Executable }))
$startedAt = [DateTimeOffset]::Now
$evidencePreexisted = Test-Path -LiteralPath $evidenceRoot -PathType Container
$evidenceWasNonEmpty = $evidencePreexisted -and @((Get-ChildItem -LiteralPath $evidenceRoot -Force -ErrorAction SilentlyContinue)).Count -gt 0

# Converts the filesystem timestamp to the same Unix nanosecond identity emitted by the Python
# smoke client. The soak must pass all three values through to every iteration of the smoke gate.
function Get-FileMtimeNanoseconds {
    param([Parameter(Mandatory)][System.IO.FileInfo]$File)

    $unixEpochTicks = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc).Ticks
    return [int64](($File.LastWriteTimeUtc.Ticks - $unixEpochTicks) * 100L)
}

# Maps paths to repository-relative values or an external placeholder so resource evidence never
# discloses a user's profile path, temporary directory name or workspace layout.
function Get-SafeRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = $repositoryRoot.TrimEnd('\') + '\'
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [System.IO.Path]::GetRelativePath($repositoryRoot, $full).Replace('\', '/')
    }
    return '<external>'
}

# Computes the byte size of one fresh data directory; failures remain explicit so a missing or
# unreadable SQLite directory cannot be interpreted as zero growth.
function Get-DirectoryBytes {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return [ordered]@{ bytes = 0; readable = $true } }
    try {
        $bytes = [int64]0
        foreach ($file in Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction Stop) { $bytes += [int64]$file.Length }
        return [ordered]@{ bytes = $bytes; readable = $true }
    } catch {
        return [ordered]@{ bytes = $null; readable = $false }
    }
}

# Takes a process snapshot by exact executable identity. Command lines and environment are
# intentionally excluded; this lets the soak distinguish Ja-owned children from unrelated tools.
function Get-OwnedProcessSnapshot {
    $snapshot = @{}
    foreach ($row in @(Get-CimInstance Win32_Process -ErrorAction Stop)) {
        if (-not $row.ExecutablePath) { continue }
        if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals([System.IO.Path]::GetFullPath([string]$row.ExecutablePath), $executablePath)) { continue }
        $process = Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            $snapshot[[int]$row.ProcessId] = [ordered]@{
                handles = [int64]$process.HandleCount
                threads = [int64]$process.Threads.Count
                workingSetBytes = [int64]$process.WorkingSet64
            }
        }
    }
    return $snapshot
}

# Aggregates one exact-process snapshot into comparable counters; sums are used because a smoke
# iteration can briefly have more than one Ja process during restart or child handoff.
function Get-ResourceCounters {
    param([Parameter(Mandatory)]$Snapshot)

    $handles = [int64]0
    $threads = [int64]0
    $workingSet = [int64]0
    foreach ($item in $Snapshot.Values) {
        $handles += [int64]$item.handles
        $threads += [int64]$item.threads
        $workingSet += [int64]$item.workingSetBytes
    }
    return [ordered]@{ processCount = [int]$Snapshot.Count; threads = $threads; handles = $handles; workingSetBytes = $workingSet }
}

# Merges a sample into a peak counter set. Keeping peak and end values separate prevents a child
# that exits before the final sample from hiding a transient thread/handle/working-set spike.
function Update-ResourcePeak {
    param([Parameter(Mandatory)]$Peak, [Parameter(Mandatory)]$Sample)

    foreach ($key in @('processCount', 'threads', 'handles', 'workingSetBytes')) {
        if ([int64]$Sample[$key] -gt [int64]$Peak[$key]) { $Peak[$key] = $Sample[$key] }
    }
}

# Launches the existing Native smoke entrypoint with ProcessStartInfo.ArgumentList, samples the
# exact sidecar while it is live, and returns only exit/resource facts. No stdout/stderr is written
# because the native smoke already emits sanitized JSON and provider credentials are out of scope.
function Invoke-TrackedNativeSmoke {
    param(
        [Parameter(Mandatory)][string]$DataDirectory,
        [Parameter(Mandatory)][string]$OutputPath,
        [Parameter(Mandatory)][string]$ExpectedSha256,
        [Parameter(Mandatory)][long]$ExpectedSizeBytes,
        [Parameter(Mandatory)][long]$ExpectedMtimeNs
    )

    $python = Get-Command -Name 'python.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $python) {
        return [ordered]@{ status = 'blocked'; blocker = 'python.exe is unavailable'; exitCode = $null; timedOut = $false; start = $null; end = $null; peak = $null; dataBytes = $null }
    }
    $arguments = @('scripts/native/run-sidecar-smoke.py', '--executable', $executablePath, '--data-dir', $DataDirectory, '--output', $OutputPath, '--timeout-seconds', '45', '--expected-sha256', $ExpectedSha256, '--expected-size', [string]$ExpectedSizeBytes, '--expected-mtime-ns', [string]$ExpectedMtimeNs)
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $python.Source
    $info.WorkingDirectory = $repositoryRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $arguments) { [void]$info.ArgumentList.Add([string]$argument) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $baselineSnapshot = Get-OwnedProcessSnapshot
    $baseline = Get-ResourceCounters -Snapshot $baselineSnapshot
    $peak = [ordered]@{ processCount = $baseline.processCount; threads = $baseline.threads; handles = $baseline.handles; workingSetBytes = $baseline.workingSetBytes }
    $timedOut = $false
    $stdoutDrain = $null
    $stderrDrain = $null
    try {
        if (-not $process.Start()) { return [ordered]@{ status = 'blocked'; blocker = 'native smoke process could not start'; exitCode = $null; timedOut = $false; start = $baseline; end = $baseline; peak = $peak; dataBytes = $null } }
        # Begin draining immediately: waiting for exit before reading redirected pipes can deadlock
        # once either child stream fills its OS pipe buffer during a long soak iteration.
        $stdoutDrain = $process.StandardOutput.ReadToEndAsync()
        $stderrDrain = $process.StandardError.ReadToEndAsync()
        while (-not $process.HasExited) {
            Update-ResourcePeak -Peak $peak -Sample (Get-ResourceCounters -Snapshot (Get-OwnedProcessSnapshot))
            if ($watch.Elapsed.TotalSeconds -gt 60) {
                $timedOut = $true
                & taskkill.exe /PID $process.Id /T /F 1> $null 2> $null
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $process.HasExited) { $process.WaitForExit(5000) | Out-Null }
        if ($null -ne $stdoutDrain) { [void]$stdoutDrain.GetAwaiter().GetResult() }
        if ($null -ne $stderrDrain) { [void]$stderrDrain.GetAwaiter().GetResult() }
        if (-not $process.HasExited) {
            return [ordered]@{ status = 'failed'; blocker = 'native-smoke-process-did-not-exit'; exitCode = $null; timedOut = $timedOut; start = $baseline; end = Get-ResourceCounters -Snapshot (Get-OwnedProcessSnapshot); peak = $peak; dataBytes = $null; dataReadable = $false }
        }
        $end = Get-ResourceCounters -Snapshot (Get-OwnedProcessSnapshot)
        Update-ResourcePeak -Peak $peak -Sample $end
        $data = Get-DirectoryBytes -Path $DataDirectory
        $smokeFailureCode = $null
        $expectedIdentityMatched = $false
        if (Test-Path -LiteralPath $OutputPath -PathType Leaf) {
            try {
                $smokeDocument = Get-Content -LiteralPath $OutputPath -Raw | ConvertFrom-Json
                $executableProperty = $smokeDocument.PSObject.Properties['executable']
                $smokeExecutable = if ($null -ne $executableProperty) { $executableProperty.Value } else { $null }
                $matchedProperty = if ($null -ne $smokeExecutable) { $smokeExecutable.PSObject.Properties['expectedIdentityMatched'] } else { $null }
                $expectedIdentityMatched = $null -ne $matchedProperty -and $matchedProperty.Value -eq $true
                if ($smokeDocument.status -eq 'failed' -and $smokeDocument.failureCode -match '^[a-z0-9-]{1,80}$') { $smokeFailureCode = [string]$smokeDocument.failureCode }
                if (-not $expectedIdentityMatched) { $smokeFailureCode = 'artifact-identity-mismatch' }
            } catch { $smokeFailureCode = 'malformed-smoke-evidence' }
        } else {
            $smokeFailureCode = 'missing-smoke-evidence'
        }
        $blocker = if ($timedOut) { 'native-smoke-deadline-exceeded' } elseif ($process.ExitCode -eq 0) { $null } elseif ($smokeFailureCode) { "native-smoke-$smokeFailureCode" } else { 'native-smoke-nonzero-exit' }
        $passed = -not $timedOut -and $process.ExitCode -eq 0 -and $expectedIdentityMatched
        return [ordered]@{ status = if ($passed) { 'passed' } else { 'failed' }; blocker = if ($passed) { $null } elseif ($blocker) { $blocker } else { 'native-smoke-artifact-identity-mismatch' }; smokeFailureCode = $smokeFailureCode; expectedIdentityMatched = $expectedIdentityMatched; exitCode = [int]$process.ExitCode; timedOut = $timedOut; durationMs = $watch.ElapsedMilliseconds; start = $baseline; end = $end; peak = $peak; dataBytes = $data.bytes; dataReadable = $data.readable }
    } catch {
        return [ordered]@{ status = 'failed'; blocker = 'native smoke process observation failed'; exitCode = 1; timedOut = $timedOut; start = $baseline; end = Get-ResourceCounters -Snapshot (Get-OwnedProcessSnapshot); peak = $peak; dataBytes = $null; dataReadable = $false }
    } finally {
        $watch.Stop()
        $process.Dispose()
    }
}

# Appends one compact sanitized JSON record to the script-owned JSONL file. Records contain no
# stdout/stderr, command line, PID or absolute path, which keeps the trend suitable for review.
function Add-SoakRecord {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Record)

    [System.IO.File]::AppendAllText($Path, ($Record | ConvertTo-Json -Compress -Depth 10) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# Removes only the fresh data subtree created by this invocation; an identity and containment
# check prevents KeepData cleanup from deleting a caller-selected workspace or prior evidence.
function Remove-OwnedDataDirectory {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = [System.IO.Path]::GetFullPath($Path)
    $prefix = $evidenceRoot.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'soak cleanup path escaped evidence root' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

# Writes the mode-specific summary even for missing executables, invalid parameters and failed
# observations. Stable blocker text is used instead of raw exception details to preserve secrecy.
function Write-SoakSummary {
    param([Parameter(Mandatory)]$Summary)

    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $path = Join-Path $evidenceRoot 'summary.json'
    [System.IO.File]::WriteAllText($path, ($Summary | ConvertTo-Json -Depth 14) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# Claims the evidence directory before validation or iteration output so concurrent standalone
# soak runners cannot merge resource samples and accidentally satisfy freshness.
function New-SoakEvidenceClaim {
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $claimPath = Join-Path $evidenceRoot '.runtime-soak-run.json'
    try {
        $stream = [System.IO.File]::Open($claimPath, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
        try {
            $claim = [ordered]@{ schemaVersion = 1; repository = 'ja'; startedAt = $startedAt.ToString('o') }
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

# Validates all numeric bounds before touching a child process. The validation result is itself
# evidence so callers can distinguish a rejected configuration from a missing native executable.
function Get-ParameterValidation {
    $errors = [System.Collections.Generic.List[string]]::new()
    if ($DurationMinutes -lt 1 -or $DurationMinutes -gt 1440) { $errors.Add('DurationMinutes must be between 1 and 1440') }
    if ($IntervalSeconds -lt 1 -or $IntervalSeconds -gt 60) { $errors.Add('IntervalSeconds must be between 1 and 60') }
    if ($MaxThreadGrowth -lt 0 -or $MaxHandleGrowth -lt 0 -or $MaxWorkingSetGrowthBytes -lt 0 -or $MaxDatabaseGrowthBytes -lt 0) { $errors.Add('resource growth limits must be non-negative') }
    $identityParts = @(
        (-not [string]::IsNullOrWhiteSpace($ExpectedSha256))
        ($ExpectedSizeBytes -gt 0)
        ($ExpectedMtimeNs -gt 0)
    )
    if (-not ($identityParts -contains $true)) { $errors.Add('expected native artifact identity is required') }
    elseif ($identityParts -contains $false) { $errors.Add('expected native artifact identity must include SHA-256, size and mtime-ns') }
    elseif ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') { $errors.Add('expected native artifact SHA-256 is invalid') }
    return [ordered]@{ passed = $errors.Count -eq 0; errors = @($errors) }
}

$validation = Get-ParameterValidation
$executableExists = Test-Path -LiteralPath $executablePath -PathType Leaf
$executableFile = if ($executableExists) { Get-Item -LiteralPath $executablePath } else { $null }
$executableBytes = if ($null -ne $executableFile) { [int64]$executableFile.Length } else { $null }
$executableSha256 = if ($executableExists) { (Get-FileHash -LiteralPath $executablePath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
$executableMtimeNs = if ($null -ne $executableFile) { Get-FileMtimeNanoseconds -File $executableFile } else { $null }
$expectedIdentityMatches = $validation.passed -and $executableSha256 -eq $ExpectedSha256.Trim().ToLowerInvariant() -and $executableBytes -eq $ExpectedSizeBytes -and $executableMtimeNs -eq $ExpectedMtimeNs
$baseSummary = [ordered]@{
    schemaVersion = 2
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    startedAt = $startedAt.ToString('o')
    mode = if ($ValidateOnly) { 'validate-only' } else { 'soak' }
    repository = 'ja'
    repositoryRoot = '<repo>'
    evidenceDirectory = Get-SafeRelativePath -Path $evidenceRoot
    evidenceDirectoryPreexisted = $evidencePreexisted
    evidenceDirectoryWasNonEmpty = $evidenceWasNonEmpty
    executable = Get-SafeRelativePath -Path $executablePath
    executableExists = $executableExists
    executableBytes = $executableBytes
    executableSha256 = $executableSha256
    expectedIdentityProvided = $validation.passed
    expectedSha256Matches = $validation.passed -and $executableSha256 -eq $ExpectedSha256.Trim().ToLowerInvariant()
    expectedSizeMatches = $validation.passed -and $executableBytes -eq $ExpectedSizeBytes
    expectedMtimeNsMatches = $validation.passed -and $executableMtimeNs -eq $ExpectedMtimeNs
    expectedIdentityMatches = $expectedIdentityMatches
    executableFreshness = if ($expectedIdentityMatches) { 'bound-to-caller-identity' } else { 'not-established-by-standalone-runner' }
    requestedDurationMinutes = $DurationMinutes
    intervalSeconds = $IntervalSeconds
    keepData = [bool]$KeepData
    limits = [ordered]@{ maxThreadGrowth = $MaxThreadGrowth; maxHandleGrowth = $MaxHandleGrowth; maxWorkingSetGrowthBytes = $MaxWorkingSetGrowthBytes; maxDatabaseGrowthBytes = $MaxDatabaseGrowthBytes }
}

if ($evidenceWasNonEmpty) {
    $baseSummary.status = 'blocked'
    $baseSummary.passed = $false
    $baseSummary.blockers = @('evidence directory is not fresh; choose a new directory')
    $baseSummary.evidenceWritten = $false
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}

if (-not (New-SoakEvidenceClaim)) {
    $baseSummary.status = 'blocked'
    $baseSummary.passed = $false
    $baseSummary.blockers = @('evidence directory is already claimed by another soak run')
    $baseSummary.evidenceWritten = $false
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}

if (-not $validation.passed) {
    $baseSummary.status = 'blocked'
    $baseSummary.passed = $false
    $baseSummary.blockers = @($validation.errors)
    Write-SoakSummary -Summary $baseSummary
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}

if (-not $executableExists -or $executableBytes -le 0) {
    $baseSummary.status = 'blocked'
    $baseSummary.passed = $false
    $baseSummary.blockers = @('native sidecar executable is missing or empty')
    $baseSummary.executableExists = $false
    Write-SoakSummary -Summary $baseSummary
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}

if (-not $expectedIdentityMatches) {
    $baseSummary.status = 'blocked'
    $baseSummary.passed = $false
    $baseSummary.blockers = @('native sidecar executable identity does not match the expected fresh artifact')
    Write-SoakSummary -Summary $baseSummary
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}

if ($ValidateOnly) {
    $baseSummary.status = 'passed'
    $baseSummary.passed = $true
    $baseSummary.blockers = @()
    $baseSummary.validation = [ordered]@{ executableFile = $true; parameters = $validation.passed; noProcessStarted = $true }
    Write-SoakSummary -Summary $baseSummary
    $baseSummary | ConvertTo-Json -Depth 14
    exit 0
}

try {
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $recordsPath = Join-Path $evidenceRoot 'iterations.jsonl'
    $baselineSnapshot = Get-OwnedProcessSnapshot
    $baseline = Get-ResourceCounters -Snapshot $baselineSnapshot
    if ($baseline.processCount -ne 0) {
        $baseSummary.status = 'blocked'
        $baseSummary.passed = $false
        $baseSummary.blockers = @('the exact soak executable is already running; resource ownership is ambiguous')
        Write-SoakSummary -Summary $baseSummary
        $baseSummary | ConvertTo-Json -Depth 14
        exit 1
    }
    $firstDataBytes = $null
    $lastDataBytes = $null
    $minimumDataBytes = $null
    $maximumDataBytes = $null
    $peakOverall = [ordered]@{ processCount = $baseline.processCount; threads = $baseline.threads; handles = $baseline.handles; workingSetBytes = $baseline.workingSetBytes }
    $iteration = 0
    $failures = [System.Collections.Generic.List[object]]::new()
    $deadline = $startedAt.AddMinutes($DurationMinutes)
    while ([DateTimeOffset]::Now -lt $deadline) {
        $iteration++
        $iterationRoot = Join-Path $evidenceRoot ('iteration-{0:D6}' -f $iteration)
        [System.IO.Directory]::CreateDirectory($iterationRoot) | Out-Null
        # Each iteration receives a clean database because the smoke intentionally uses fixed
        # protocol identities. Comparing equivalent fresh runs detects footprint drift without
        # converting duplicate-key failures into a false long-soak regression.
        $iterationDataRoot = Join-Path $iterationRoot 'data'
        $smoke = Invoke-TrackedNativeSmoke -DataDirectory $iterationDataRoot -OutputPath (Join-Path $iterationRoot 'smoke.json') -ExpectedSha256 $ExpectedSha256.Trim().ToLowerInvariant() -ExpectedSizeBytes $ExpectedSizeBytes -ExpectedMtimeNs $ExpectedMtimeNs
        $peak = if ($null -ne $smoke.peak) { $smoke.peak } else { $baseline }
        Update-ResourcePeak -Peak $peakOverall -Sample $peak
        $dataBytes = $smoke.dataBytes
        if ($null -eq $firstDataBytes -and $null -ne $dataBytes) { $firstDataBytes = [int64]$dataBytes }
        if ($null -ne $dataBytes) {
            $lastDataBytes = [int64]$dataBytes
            if ($null -eq $minimumDataBytes -or $lastDataBytes -lt $minimumDataBytes) { $minimumDataBytes = $lastDataBytes }
            if ($null -eq $maximumDataBytes -or $lastDataBytes -gt $maximumDataBytes) { $maximumDataBytes = $lastDataBytes }
        }
        $resourceGrowth = [ordered]@{
            threads = [int64]$peak.threads - [int64]$baseline.threads
            handles = [int64]$peak.handles - [int64]$baseline.handles
            workingSetBytes = [int64]$peak.workingSetBytes - [int64]$baseline.workingSetBytes
        }
        $databaseGrowth = if ($null -ne $firstDataBytes -and $null -ne $dataBytes) { [int64]$dataBytes - [int64]$firstDataBytes } else { $null }
        $afterSnapshot = Get-OwnedProcessSnapshot
        $newProcessCount = @($afterSnapshot.Keys | Where-Object { -not $baselineSnapshot.ContainsKey($_) }).Count
        # Only the count of new exact-path identities is retained; PID values and command lines
        # are deliberately omitted from the record.
        $violations = [System.Collections.Generic.List[string]]::new()
        if ($resourceGrowth.threads -gt $MaxThreadGrowth) { $violations.Add('thread-growth-limit') }
        if ($resourceGrowth.handles -gt $MaxHandleGrowth) { $violations.Add('handle-growth-limit') }
        if ($resourceGrowth.workingSetBytes -gt $MaxWorkingSetGrowthBytes) { $violations.Add('working-set-growth-limit') }
        if ($null -ne $databaseGrowth -and $databaseGrowth -gt $MaxDatabaseGrowthBytes) { $violations.Add('database-growth-limit') }
        if ($newProcessCount -ne 0) { $violations.Add('residual-process-after-iteration') }
        if ($smoke.status -ne 'passed') { $violations.Add($smoke.blocker) }
        $record = [ordered]@{
            iteration = $iteration
            completedAt = [DateTimeOffset]::Now.ToString('o')
            status = if ($violations.Count -eq 0) { 'passed' } else { 'failed' }
            exitCode = $smoke.exitCode
            timedOut = $smoke.timedOut
            durationMs = if ($null -ne $smoke.PSObject.Properties['durationMs']) { $smoke.durationMs } else { $null }
            currentProcessCount = $newProcessCount
            resource = [ordered]@{ start = $smoke.start; end = $smoke.end; peak = $smoke.peak; growthFromBaseline = $resourceGrowth }
            databaseBytes = $dataBytes
            databaseGrowthBytes = $databaseGrowth
            violations = @($violations)
            smokeEvidence = [System.IO.Path]::GetRelativePath($evidenceRoot, (Join-Path $iterationRoot 'smoke.json')).Replace('\', '/')
        }
        Add-SoakRecord -Path $recordsPath -Record $record
        if (-not $KeepData -and (Test-Path -LiteralPath $iterationDataRoot)) { Remove-OwnedDataDirectory -Path $iterationDataRoot }
        if ($violations.Count -gt 0) { $failures.Add([ordered]@{ iteration = $iteration; violations = @($violations) }) }
        if ($violations.Count -gt 0) { break }
        if ([DateTimeOffset]::Now.AddSeconds($IntervalSeconds) -lt $deadline) { Start-Sleep -Seconds $IntervalSeconds }
    }

    $finalSnapshot = Get-OwnedProcessSnapshot
    $final = Get-ResourceCounters -Snapshot $finalSnapshot
    $residualProcessCount = @($finalSnapshot.Keys | Where-Object { -not $baselineSnapshot.ContainsKey($_) }).Count
    $baseSummary.status = if ($failures.Count -eq 0 -and $iteration -gt 0 -and [DateTimeOffset]::Now -ge $deadline -and $residualProcessCount -eq 0) { 'passed' } else { 'failed' }
    $baseSummary.passed = $baseSummary.status -eq 'passed'
    $baseSummary.iterations = $iteration
    $baseSummary.failures = @($failures)
    $baseSummary.residualProcessCount = $residualProcessCount
    $baseSummary.resourceTrend = [ordered]@{ baseline = $baseline; final = $final; peak = $peakOverall; peakGrowthFromBaseline = [ordered]@{ threads = [int64]$peakOverall.threads - [int64]$baseline.threads; handles = [int64]$peakOverall.handles - [int64]$baseline.handles; workingSetBytes = [int64]$peakOverall.workingSetBytes - [int64]$baseline.workingSetBytes }; limits = $baseSummary.limits }
    $baseSummary.databaseTrend = [ordered]@{ firstBytes = $firstDataBytes; lastBytes = $lastDataBytes; minimumBytes = $minimumDataBytes; maximumBytes = $maximumDataBytes; growthBytes = if ($null -ne $firstDataBytes -and $null -ne $lastDataBytes) { [int64]$lastDataBytes - [int64]$firstDataBytes } else { $null }; isolatedDatabasePerIteration = $true }
    $baseSummary.blockers = @($failures | ForEach-Object { $_.violations })
    Write-SoakSummary -Summary $baseSummary
    $baseSummary | ConvertTo-Json -Depth 14
    if (-not $baseSummary.passed) { exit 1 }
} catch {
    $baseSummary.status = 'failed'
    $baseSummary.passed = $false
    $baseSummary.blockers = @('runtime-soak-runner-exception')
    try { Write-SoakSummary -Summary $baseSummary } catch { }
    $baseSummary | ConvertTo-Json -Depth 14
    exit 1
}
