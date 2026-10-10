# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$EvidenceDirectory = '.tmp/verification/runtime-soak',
    [string]$Executable = 'ja-agent/ja-app-server/target/ja-app-server.exe',
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

# 将文件系统时间戳转换为与 Python smoke 客户端相同的 Unix 纳秒标识；soak 每轮调用 smoke 门禁都必须传入这三个值。
function Get-FileMtimeNanoseconds {
    param([Parameter(Mandatory)][System.IO.FileInfo]$File)

    $unixEpochTicks = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc).Ticks
    return [int64](($File.LastWriteTimeUtc.Ticks - $unixEpochTicks) * 100L)
}

# 将路径映射为仓库相对路径或外部占位值，避免资源证据泄露用户配置目录、临时目录名或工作区结构。
function Get-SafeRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = $repositoryRoot.TrimEnd('\') + '\'
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [System.IO.Path]::GetRelativePath($repositoryRoot, $full).Replace('\', '/')
    }
    return '<external>'
}

# 计算一份新数据目录的字节数；读取失败必须明确呈现，不能把缺失或不可读的 SQLite 目录当作零增长。
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

# 按可执行文件精确身份采集进程快照。快照不含命令行和环境变量，以便区分 Ja 子进程与无关工具。
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

# 将同一进程身份的快照汇总为可比较计数；重启或子进程交接期间，一轮 smoke 可能短暂存在多个 Ja 进程，因此按资源值求和。
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

# 将一次采样合并到峰值计数。分别保留峰值和结束值，避免子进程在最终采样前退出而掩盖线程、句柄或工作集的瞬时峰值。
function Update-ResourcePeak {
    param([Parameter(Mandatory)]$Peak, [Parameter(Mandatory)]$Sample)

    foreach ($key in @('processCount', 'threads', 'handles', 'workingSetBytes')) {
        if ([int64]$Sample[$key] -gt [int64]$Peak[$key]) { $Peak[$key] = $Sample[$key] }
    }
}

# 使用 ProcessStartInfo.ArgumentList 启动现有 Native smoke 入口，在 sidecar 存活期间采样，并且只返回退出状态和资源数据。
# 不写入 stdout/stderr，因为 Native smoke 已输出净化后的 JSON，Provider 凭据不属于本报告范围。
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
        # 立即开始排空重定向管道：长时间 soak 中，任一子进程输出流填满操作系统管道缓冲区后再等待退出会造成死锁。
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

# 向脚本管理的 JSONL 文件追加一条精简净化记录。记录不含 stdout/stderr、命令行、PID 或绝对路径，便于审阅趋势。
function Add-SoakRecord {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Record)

    [System.IO.File]::AppendAllText($Path, ($Record | ConvertTo-Json -Compress -Depth 10) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# 只删除本次运行新建的数据子目录；身份与路径包含关系检查可避免 KeepData 清理调用方指定的工作区或既有证据。
function Remove-OwnedDataDirectory {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = [System.IO.Path]::GetFullPath($Path)
    $prefix = $evidenceRoot.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'soak cleanup path escaped evidence root' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

# 即使可执行文件缺失、参数无效或采样失败，也要写入对应模式的摘要。为保护敏感信息，使用稳定的阻塞说明而不保留原始异常。
function Write-SoakSummary {
    param([Parameter(Mandatory)]$Summary)

    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $path = Join-Path $evidenceRoot 'summary.json'
    [System.IO.File]::WriteAllText($path, ($Summary | ConvertTo-Json -Depth 14) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# 在校验或写入迭代结果前先独占证据目录，避免并发运行的独立 soak 合并资源采样并误满足新鲜度要求。
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

# 接触子进程前先校验所有数值边界。校验结果本身也作为证据，便于调用方区分配置被拒与 Native 可执行文件缺失。
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
        # 每轮都使用干净数据库，因为 smoke 有意采用固定协议标识。比较条件相同的新运行可发现资源占用漂移，
        # 同时避免把重复键错误误判为长时间 soak 回归。
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
        # 只保留新增精确路径身份的数量；记录中有意省略 PID 和命令行。
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
