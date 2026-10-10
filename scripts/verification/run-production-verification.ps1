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
    [switch]$IncludeRuntimeRefresh,
    [switch]$IncludePlan,
    [ValidateRange(120, 1440)][int]$PlanSoakDurationMinutes = 120,
    [switch]$IncludeTurnChangeReview,
    [switch]$IncludeRealProvider,
    [string]$CorrespondingSourcePath = '',
    [string[]]$ArtifactPath = @(),
    [switch]$LocalVerification,
    [switch]$ListOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot '..\lib\diagnostics.ps1')
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
$javaModuleBuildDirectory = 'target-ja-verification-' + [Guid]::NewGuid().ToString('N')
$javaProductVersion = [string]((Get-Content -LiteralPath (Join-Path $repositoryRoot 'package.json') -Raw | ConvertFrom-Json).version)
$javaTestTargetDirectory = Join-Path $evidenceRoot 'java-test-target'
$javaArtifactDirectory = Join-Path $evidenceRoot 'java-target'
$javaArtifactPath = Join-Path $javaArtifactDirectory 'ja-app-server.jar'
$interactionPlanSidecarDirectory = Join-Path $evidenceRoot 'interaction-plan-sidecar'
$turnChangeReviewSidecarDirectory = Join-Path $evidenceRoot 'turn-change-review-sidecar'
$turnChangeReviewTargetTriple = 'x86_64-pc-windows-msvc'
$turnChangeReviewSourceCommit = [string]((& git.exe -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -First 1))
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
# Plan 与 Turn Change Review 的发布门禁必须同时证明 Native Image；单独请求 UI 或
# soak 而跳过 Native 会让 JVM sidecar 的结果被错误提升为生产可执行文件证据。
$nativeRequested = [bool]$IncludeNative -or [bool]$IncludeSoak -or [bool]$IncludePlan `
    -or [bool]$IncludeTurnChangeReview
$freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
$sbomInputMaxAge = [TimeSpan]::FromHours(24)
# 当前发布矩阵中最大的产物为 106,893,312 字节；120 MiB 的复制产物上限留有余量，同时仍能约束已验证的 Native Image 目标。
$nativeExecutableMaxBytes = [int64](120 * 1024 * 1024)

# 此封闭集合与 Native smoke 客户端保持一致。runner 必须逐项检查每种能力，因为进程或顶层状态通过并不能证明可执行文件
# 实际覆盖了所有阻止发布的路径。
$requiredNativeSmokeSubgates = @(
    'jsonSchema', 'configAuth', 'okhttpSse', 'mcp',
    'shellCancellation', 'shellStdinEof', 'sqlite', 'recovery', 'networknt'
)

# 仅接受相关门禁开始后生成且时间戳未明显超前的文件。时间上限可防止复制来的文件或伪造时钟的产物满足本轮门禁。
function Test-FreshTimestamp {
    param(
        [Parameter(Mandatory)][DateTime]$LastWriteTimeUtc,
        [Parameter(Mandatory)][DateTimeOffset]$Since
    )

    return $LastWriteTimeUtc -ge $Since.UtcDateTime `
        -and $LastWriteTimeUtc -le [DateTime]::UtcNow.Add($freshnessFutureSkew)
}

# SBOM 源码及归档输入必须在 runner 启动前存在，因此其新鲜度窗口不能像生成的证据那样从 runStartedAt 开始计算。
# 有界时间窗口仍会拒绝过期或未来时间的输入，并在法务审查前记录其路径、范围和内容身份。
function Test-FreshSbomInputTimestamp {
    param(
        [Parameter(Mandatory)][DateTime]$LastWriteTimeUtc,
        [Parameter(Mandatory)][DateTimeOffset]$Since
    )

    return $LastWriteTimeUtc -ge $Since.UtcDateTime.Subtract($sbomInputMaxAge) `
        -and $LastWriteTimeUtc -le [DateTime]::UtcNow.Add($freshnessFutureSkew)
}

# 将文件系统时间戳转换为与 Python smoke 客户端相同的 Unix 纳秒标识。与文件大小和 SHA-256 一起保留此值，
# 可要求每个调用方证明启动的是新鲜度门禁检查过的同一产物，包括复用原路径的重新构建。
function Get-FileMtimeNanoseconds {
    param([Parameter(Mandatory)][System.IO.FileInfo]$File)

    $unixEpochTicks = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc).Ticks
    return [int64](($File.LastWriteTimeUtc.Ticks - $unixEpochTicks) * 100L)
}

# 为证据返回稳定的仓库相对路径，并明确标记调用方提供的外部路径。这样报告可移植，也不会把用户名或含敏感信息的临时路径
# 纳入证据身份。
function Get-SafePath {
    param([Parameter(Mandatory)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = $repositoryRoot.TrimEnd('\') + '\'
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [System.IO.Path]::GetRelativePath($repositoryRoot, $full).Replace('\', '/')
    }
    return '<external>'
}

# 解析并校验调用方选定的 JDK，但不持久化版本横幅。生产验证明确要求 Java 25：不接受 PATH 回退，
# 因为其他 JDK 可能悄然改变 Maven、Solon AOT 和桌面子进程运行环境。
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
        # 现有 smoke 客户端继续使用各自明确限定的变量；新的调用方统一以 JA_TEST_JAVA 作为 runner 级配置来源。
        $commonEnvironment['JA_REAL_PROVIDER_JAVA'] = $javaExecutable
    } catch {
        $script:javaHomeIssue = 'selected Java 25 executable could not be resolved or executed'
    }
}

# 仅返回选定的 Java 身份和主版本号，不返回版本命令的完整输出。这样既能审计所用 JDK，
# 又能避免把主机路径或供应商横幅写入可移植证据。
function Get-Java25Evidence {
    if ($javaHomeIssue) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = $javaHomeIssue; javaMajor = $null; javaHome = $null; executable = $null }
    }
    if ($null -eq $javaExecutable -or $javaMajor -ne 25) {
        return [ordered]@{ passed = $false; status = 'blocked'; blocker = 'Java 25 preflight did not execute'; javaMajor = $null; javaHome = $null; executable = $null }
    }
    return [ordered]@{ passed = $true; status = 'passed'; blocker = $null; javaMajor = $javaMajor; javaHome = Get-SafePath -Path (Split-Path -Parent (Split-Path -Parent $javaExecutable)); executable = Get-SafePath -Path $javaExecutable }
}

# 将 runner 级 Java/Cargo 变量与单个门禁的小范围覆盖值合并，但不修改共享哈希表。这样可防止一个 Provider 或桌面调用
# 将运行模式泄漏给后续门禁，同时保证每个子进程使用同一指定 JDK。
function Merge-Environment {
    param([Parameter(Mandatory)][hashtable]$Base, [Parameter(Mandatory)][hashtable]$Overrides)

    $merged = @{}
    foreach ($entry in $Base.GetEnumerator()) { $merged[$entry.Key] = $entry.Value }
    foreach ($entry in $Overrides.GetEnumerator()) { $merged[$entry.Key] = $entry.Value }
    return $merged
}

# 将单个门禁记录为数据，而不是拼接 shell 命令；该记录是 ListOnly 的事实来源，也使 runner 能不受执行顺序影响地报告
# 门禁是已请求、已跳过还是被阻塞。
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

# 只把非敏感命令参数转换为展示文本，实际调用仍接收原始 argv 数组；ListOnly 输出不包含主机路径，可安全用于审查。
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

# 只写入脱敏后的 stdout/stderr 并返回可移植文件名；原始子进程输出在内存中保留到本函数完成脱敏，避免 Provider 回显内容进入日志。
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
    [System.IO.File]::WriteAllText($stdoutPath, (ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value ($Stdout -join [Environment]::NewLine)) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($stderrPath, (ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value ($Stderr -join [Environment]::NewLine)) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    return [ordered]@{ stdout = [System.IO.Path]::GetRelativePath($evidenceRoot, $stdoutPath).Replace('\', '/'); stderr = [System.IO.Path]::GetRelativePath($evidenceRoot, $stderrPath).Replace('\', '/') }
}

# 从仓库根目录使用结构化 argv 数组和临时进程环境调用一条命令。单独封装此边界，便于验证 cwd、环境还原、脱敏和失败身份，
# 而不必修改任何生产命令入口。恢复时必须区分缺失与显式空值；PowerShell 7.6 会把普通 null 参数绑定为空字符串，
# 因此缺失值用 NullString 明确删除，避免临时 CARGO_TARGET_DIR 污染下一条命令。
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
        # Maven/Cargo/Vitest 的结论和动态计数通常出现在输出末尾，因此保留有界尾部。
        # 这也避免嘈杂的子进程或 Provider 在脱敏前耗尽控制器内存。
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
        $stderr.Add((ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.Exception.Message))
        $exitCode = 1
    } finally {
        $watch.Stop()
        foreach ($entry in $priorEnvironment.GetEnumerator()) {
            if ($null -eq $entry.Value) {
                [Environment]::SetEnvironmentVariable($entry.Key, [System.Management.Automation.Language.NullString]::Value, 'Process')
            } else {
                [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
            }
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

# 使用与 Python 门禁相同的名称和字节顺序计算合同语料摘要及实际帧数。摘要直接取自当前文件，
# 因此旧的消费者结果不会被误认为是针对已变更协议语料的证据。
function Get-ContractCorpusEvidence {
    $goldenRoot = Join-Path $repositoryRoot 'contracts\golden'
    if (-not (Test-Path -LiteralPath $goldenRoot -PathType Container)) {
        return [ordered]@{ status = 'blocked'; blocker = 'contract corpus directory is missing'; gateDigest = $null; gateValidFrames = $null; gateInvalidFrames = $null; gateConsumers = @() }
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
    return [ordered]@{ status = 'passed'; digest = $digest; fileCount = $fileCount; validFrames = $validCount; invalidFrames = $invalidCount; gateDigest = $null; gateValidFrames = $null; gateInvalidFrames = $null; gateConsumers = @() }
}

# 从所有生产模块和示例模块发现 Surefire 报告，并区分本次门禁生成的证据和过期文件。带模块限定的身份可区分同名测试报告。
function Get-MavenSurefireReports {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $configuredTarget = Get-Variable -Name javaTestTargetDirectory -ErrorAction SilentlyContinue
    $targetRoot = if ($null -ne $configuredTarget) {
        [string]$configuredTarget.Value
    } else {
        Join-Path $repositoryRoot 'ja-agent/ja-app-server\target'
    }
    $moduleBuildDirectory = Get-Variable -Name javaModuleBuildDirectory -ErrorAction SilentlyContinue
    $moduleBuildDirectoryName = if ($null -ne $moduleBuildDirectory) {
        [string]$moduleBuildDirectory.Value
    } else {
        'target'
    }
    $moduleTargets = [System.Collections.Generic.List[object]]::new()
    foreach ($module in @(
            @{ id = 'ja-agent-core'; path = 'ja-agent/ja-agent-core' }
            @{ id = 'ja-agent-store-local'; path = 'ja-agent/extensions/ja-agent-store-local' }
            @{ id = 'ja-agent-store-pg'; path = 'ja-agent/extensions/ja-agent-store-pg' }
            @{ id = 'ja-agent-skills-local'; path = 'ja-agent/extensions/ja-agent-skills-local' }
            @{ id = 'ja-agent-skills-pg'; path = 'ja-agent/extensions/ja-agent-skills-pg' }
            @{ id = 'ja-agent-example-springboot'; path = 'ja-agent/example/ja-agent-example-springboot' }
        )) {
        $moduleTargets.Add([pscustomobject]@{
                id = $module.id
                path = Join-Path (Join-Path $repositoryRoot $module.path) $moduleBuildDirectoryName
            })
    }
    $moduleTargets.Add([pscustomobject]@{ id = 'ja-app-server'; path = $targetRoot })
    $defaultAppTarget = Join-Path $repositoryRoot 'ja-agent/ja-app-server\target'
    if (-not [System.IO.Path]::GetFullPath($defaultAppTarget).Equals(
            [System.IO.Path]::GetFullPath($targetRoot), [System.StringComparison]::OrdinalIgnoreCase)) {
        $moduleTargets.Add([pscustomobject]@{ id = 'ja-app-server'; path = $defaultAppTarget })
    }

    $reportRoots = [System.Collections.Generic.List[object]]::new()
    $all = [System.Collections.Generic.List[object]]::new()
    foreach ($moduleTarget in $moduleTargets) {
        if (-not (Test-Path -LiteralPath $moduleTarget.path -PathType Container)) { continue }
        foreach ($reportRoot in @(Get-ChildItem -LiteralPath $moduleTarget.path -Directory -Filter 'surefire-reports' -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Unique)) {
            $reportRoots.Add($reportRoot)
            foreach ($report in @(Get-ChildItem -LiteralPath $reportRoot.FullName -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)) {
                $relativeIdentity = [System.IO.Path]::GetRelativePath($moduleTarget.path, $report.FullName).Replace('\', '-').Replace('/', '-')
                $all.Add([pscustomobject]@{
                        Name = "TEST-$($moduleTarget.id)--$relativeIdentity"
                        FullName = $report.FullName
                        Length = $report.Length
                        LastWriteTimeUtc = $report.LastWriteTimeUtc
                    })
            }
        }
    }
    return [ordered]@{
        all = @($all.ToArray())
        fresh = @($all.ToArray() | Where-Object { Test-FreshTimestamp -LastWriteTimeUtc $_.LastWriteTimeUtc -Since $Since })
        roots = @($reportRoots.ToArray())
    }
}

# 解析有意设置 useFile=false 的构建所输出的 Surefire 控制台汇总。逐类标记只记录为 observedSuites；
# 有界命令采集可能截断这些标记，因此绝不能将其作为精确套件数量报告。
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

# 在后续打包门禁清理或替换 Maven target 前复制新鲜 XML。每个文件先复制到同级临时文件，受单文件和总大小限制，
# 再次检查源文件是否变化，最后移入专用证据目录；遇到任何不确定情况都失败关闭。
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
    if (@($reportSet.fresh).Count -eq 0) {
        $base.status = 'stale'
        $base.blocker = 'Maven Surefire report set contains no report from the current gate'
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

# Java 门禁完成后只读取打包前的 Surefire 快照。确实没有 XML 的构建可以使用本次门禁的控制台输出；
# 过期或失败的快照绝不回退读取控制台，以免成功的 Maven 退出码掩盖 target 清理或部分复制缺陷。
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

# 读取可能缺失的 JSON 数值字段，不让 StrictMode 把不完整的 reporter 夹具变成 runner 异常；缺失计数会明确保留为零，供审查。
function Get-JsonIntField {
    param([Parameter(Mandatory)]$Document, [Parameter(Mandatory)][string]$Name)

    $property = $Document.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return 0 }
    return [int]$property.Value
}

# 解析 Vitest JSON reporter 输出并保留工具提供的动态总数。该文件必须新于本次运行，防止先前通过的报告再次满足门禁。
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

# 从脱敏日志中提取 Rust runtime、desktop 和 CLI 的所有测试结果行，不依赖 Cargo JSON schema，也不把原始测试输出写入 JSON。
function Get-RustTestEvidence {
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($result in $results | Where-Object { $_.name -in @('rust-runtime-tests', 'rust-tauri-tests', 'rust-cli-tests') }) {
        # PowerShell 正则运算符会写入不区分大小写的自动变量 `$Matches`。
        # 此处必须使用不同名称，否则首条结果行会覆盖这个类型化列表。
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

# 解析当前三端消费者合同标记，并与新计算的语料摘要合并；有意不接受已淘汰的工具专属标记字段作为兼容路径。
function Get-ContractTestEvidence {
    $result = $results | Where-Object name -eq 'contract' | Select-Object -First 1
    $evidence = Get-ContractCorpusEvidence
    if ($null -eq $result) { $evidence.status = 'blocked'; $evidence.blocker = 'contract step did not execute'; return $evidence }
    foreach ($logName in @($result.stdout, $result.stderr)) {
        if ([string]::IsNullOrWhiteSpace($logName)) { continue }
        $path = Join-Path $evidenceRoot $logName
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        foreach ($line in Get-Content -LiteralPath $path) {
            if ($line -match 'CONTRACT_GATE_OK\s+digest=([0-9a-f]{64})\s+validFrames=(\d+)\s+invalidFrames=(\d+)\s+consumers=([a-z]+(?:,[a-z]+)*)') {
                $evidence.gateDigest = $Matches[1]
                $evidence.gateValidFrames = [int]$Matches[2]
                $evidence.gateInvalidFrames = [int]$Matches[3]
                $evidence.gateConsumers = @($Matches[4] -split ',')
            }
        }
    }
    if (-not $result.passed) { $evidence.status = 'failed'; $evidence.blocker = 'contract command failed' }
    elseif ($evidence.gateDigest -ne $evidence.digest -or $evidence.gateValidFrames -ne $evidence.validFrames `
            -or $evidence.gateInvalidFrames -ne $evidence.invalidFrames `
            -or (@($evidence.gateConsumers) -join ',') -ne 'java,rust,typescript') {
        $evidence.status = 'failed'
        $evidence.blocker = 'contract gate marker does not match current corpus and consumer set'
    }
    return $evidence
}

# 对已打包的 server、Spring Boot 示例和桌面产物计算哈希，并将每项必需产物绑定到生成它的门禁，避免旧文件满足本轮验证。
function Get-ArtifactEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $configuredJavaArtifact = Get-Variable -Name javaArtifactPath -ErrorAction SilentlyContinue
    $effectiveJavaArtifactPath = if ($null -ne $configuredJavaArtifact) {
        [string]$configuredJavaArtifact.Value
    } else {
        Join-Path $repositoryRoot 'ja-agent/ja-app-server\target\ja-app-server.jar'
    }
    $springBootExamplePath = "ja-agent/example/ja-agent-example-springboot/$javaModuleBuildDirectory/ja-agent-example-springboot-$javaProductVersion-exec.jar"
    $specs = @(
        [ordered]@{ key = 'javaJar'; path = [System.IO.Path]::GetRelativePath($repositoryRoot, $effectiveJavaArtifactPath).Replace('\', '/'); required = $true; producedBy = 'java-artifact-package'; maxBytes = 268435456 },
        [ordered]@{ key = 'springBootExampleJar'; path = $springBootExamplePath; required = $true; producedBy = 'java-jvm'; maxBytes = 268435456 },
        [ordered]@{ key = 'nativeExe'; path = 'ja-agent/ja-app-server/target/ja-app-server.exe'; required = $nativeRequested; producedBy = 'java-native-build'; maxBytes = $nativeExecutableMaxBytes },
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
        # 新鲜度依据成功执行的生成门禁，而不只是 runner 的总时钟。若打包步骤未执行，此检查会拒绝过期或时间超前的产物。
        $fresh = $null -ne $producer -and $producer.passed -and $null -ne $producerStartedAt `
            -and (Test-FreshTimestamp -LastWriteTimeUtc $file.LastWriteTimeUtc -Since $producerStartedAt)
        $bytes = [int64]$file.Length
        $passed = $fresh -and $bytes -le $spec.maxBytes
        $artifacts.Add([ordered]@{ key = $spec.key; path = $spec.path; required = $true; status = if ($passed) { 'passed' } else { 'failed' }; passed = $passed; exists = $true; freshThisRun = $fresh; producerPassed = [bool]($null -ne $producer -and $producer.passed); actualBytes = $bytes; bytes = $bytes; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); mtimeNs = Get-FileMtimeNanoseconds -File $file; lastWriteTime = $file.LastWriteTime.ToString('o'); maxBytes = $spec.maxBytes; producedBy = $spec.producedBy })
    }
    return @($artifacts)
}

# 识别仓库内的构建和缓存根目录，包括每个 Maven 模块中的隔离 target；源码和本轮证据目录始终保留在扫描范围内。
# Cargo target 可能包含生成的 JavaScript 和复制来的依赖源码；把构建输出当成人工编写源码扫描会重复检查，
# 还会使摘要耗时随编译缓存大小增长。
function Test-GeneratedRepositoryPath {
    param([Parameter(Mandatory)][string]$RelativePath)

    $path = $RelativePath.Replace('\', '/')
    if ($path.StartsWith('./', [System.StringComparison]::Ordinal)) { $path = $path.Substring(2) }
    return $path -match '^(?:\.tmp|dist|node_modules|target(?:-[^/]+)?|ja-agent/(?:ja-agent-core|ja-app-server|example/ja-agent-example-springboot|extensions/(?:ja-agent-store-local|ja-agent-store-pg|ja-agent-skills-local|ja-agent-skills-pg))/(?:target(?:-[^/]+)?)|apps/desktop/dist)(?:/|$)'
}

# 扫描已变更源码和本轮证据中的凭据形态字面值，只返回路径、行号和规则元数据；有意不输出匹配到的源码或具体值。
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
        # JVM class 文件的常量池可能包含夹具字面值，但它们是二进制构建证据，不属于人工编写或持久化的文本面。
        # 源文件以及所有可读报告和日志仍在扫描范围内，因此此排除不会隐藏随包发布的凭据。
        if ($extension -in @('.png', '.jpg', '.jpeg', '.gif', '.ico', '.exe', '.dll', '.class', '.jar', '.db', '.sqlite', '.woff', '.woff2')) { continue }
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

# 按精确路径检查 App Server、桌面和本地 CLI 可执行文件；不会按进程名匹配无关进程，也不会记录命令行。残留子进程会阻止通过。
function Get-ResidualProcessEvidence {
    $candidatePaths = @(
        (Join-Path $repositoryRoot 'ja-agent/ja-app-server\target\ja-app-server.exe'),
        (Join-Path $repositoryRoot 'target\debug\ja-desktop.exe'),
        (Join-Path $repositoryRoot 'target\release\ja-desktop.exe'),
        (Join-Path $repositoryRoot 'target\ja-cli\ja.exe')
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

# 执行明确的产物大小预算和新鲜度要求，不会把缺失的构建输出当作通过。每项产物都记录阈值，
# 以便后续审查时有依据地调整预算，而不是把阈值藏在数量断言里。
function Get-SizeGateEvidence {
    param([Parameter(Mandatory)][object[]]$Artifacts)

    $findings = [System.Collections.Generic.List[object]]::new()
    foreach ($artifact in $Artifacts | Where-Object required) {
        $producer = $results | Where-Object name -eq $artifact.producedBy | Select-Object -First 1
        # 生成步骤未成功时，前置条件失败已经决定了结论。再次把旧文件报告为过期会掩盖真正阻止发布的依赖项。
        if ($null -eq $producer -or -not $producer.passed) { continue }
        if (-not $artifact.exists) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-missing' }); continue }
        if (-not $artifact.freshThisRun) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-stale' }) }
        if ([int64]$artifact.actualBytes -gt [int64]$artifact.maxBytes) { $findings.Add([ordered]@{ path = $artifact.path; rule = 'artifact-too-large' }) }
    }
    return [ordered]@{ passed = $findings.Count -eq 0; findingCount = $findings.Count; findings = @($findings) }
}

# 验证实际 Maven 组合根中的 Native Image 配置包含必需的 no-fallback 标志。该门禁独立运行，
# 避免配置被修改或省略时，Maven 命令成功被误认为 no-fallback 构建成功。
function Get-NativePolicyEvidence {
    $pomPath = Join-Path $repositoryRoot 'ja-agent/ja-app-server\pom.xml'
    if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) { return [ordered]@{ passed = $false; blocker = 'Native Image Maven POM is missing' } }
    $text = Get-Content -LiteralPath $pomPath -Raw
    $matches = @([regex]::Matches($text, '<buildArg>\s*--no-fallback\s*</buildArg>'))
    return [ordered]@{ passed = $matches.Count -eq 1; noFallbackFlagCount = $matches.Count; blocker = if ($matches.Count -eq 1) { $null } else { 'Native Image profile must contain exactly one --no-fallback build argument' } }
}

# 防止把先前运行的日志或 reporter 文件误当成本次运行的证明；允许预先创建空目录，非空目录则作为硬阻塞。
function Get-EvidenceFreshnessEvidence {
    return [ordered]@{ passed = -not $evidenceWasNonEmpty; blocker = if ($evidenceWasNonEmpty) { 'evidence directory is not fresh; choose a new directory' } else { $null }; preexisted = $evidencePreexisted; wasNonEmpty = $evidenceWasNonEmpty }
}

# 在任何子门禁写入前，使用 CreateNew 独占空证据目录。两个并发 runner 若指向同一目录，就不能合并日志伪造新鲜报告。
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

# smoke 步骤开始前，要求 Native Image 可执行文件由本次 no-fallback 构建生成。返回实际文件大小和哈希，
# 可让 smoke 依赖于新鲜产物，而不是之前构建遗留的旧可执行文件。
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

# 校验 smoke 报告是否覆盖所有必需能力。缺失或阻塞项与失败项分别记录，便于区分能力未执行与报告状态自相矛盾；
# 两类情况都不能满足 Native 门禁。
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

# 读取本次生成的 smoke 报告，并要求 smoke 客户端确认新鲜度门禁提供的三个确切身份值。进程成功退出本身并不能证明通过：
# 身份报告缺失或遭篡改时，生产摘要仍必须判定为硬失败。
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

# 仅通过通用变量名检查用户选定的 Provider 配置。变量值绝不复制到步骤、异常、输出流或摘要中；缺少凭据时，真实 Provider 门禁明确标记为阻塞。
function Get-ProviderPreflightEvidence {
    $required = @('JA_REAL_PROVIDER_API_KEY', 'JA_REAL_PROVIDER_BASE_URL',
        'JA_REAL_PROVIDER_MODEL', 'JA_REAL_PROVIDER_API')
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) })
    $authorized = [Environment]::GetEnvironmentVariable('JA_REAL_PROVIDER_AUTHORIZED', 'Process') -eq '1'
    return [ordered]@{ passed = $missing.Count -eq 0 -and $authorized; authorized = $authorized; missingVariables = $missing; blocker = if (-not $authorized) { 'real provider execution was not explicitly authorized for this verification run' } elseif ($missing.Count -ne 0) { 'provider credential, loopback URL, model or API specification is not present in the process environment' } else { $null } }
}

# 在允许成熟的生成器运行前，先校验法务 SBOM 输入。两类输入都必须同时提供、使用绝对路径、位于仓库范围内且适用于本轮；
# 这样既可防止过期或私有路径成为发布证据，也可确保缺少法务输入时 Native 门禁保持阻塞，而不会套用生成器默认值。
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
            $fresh = Test-FreshSbomInputTimestamp -LastWriteTimeUtc $latestWrite -Since $Since
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

# 校验固定版本 CycloneDX 生成的确切 BOM，包括新鲜度和内容哈希。后续许可证报告必须与该哈希比对，
# 不能仅凭 target 中同名的既有文件作判断。
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

# 读取成熟 SBOM 生成器的有界报告，并计算每个新鲜 JSON 产物的哈希。本地实施验收只使用 generationPassed；
# status 和 blockerCodes 始终保留发布结论，避免 dirty 工作树或未批准的许可证归档被误称为发布通过。
function Get-SbomLicenseEvidence {
    param([Parameter(Mandatory)][DateTimeOffset]$Since)

    $root = Join-Path $evidenceRoot 'sbom'
    $inputGate = $results | Where-Object name -eq 'sbom-inputs' | Select-Object -First 1
    if ($null -eq $inputGate -or -not $inputGate.executed -or -not $inputGate.passed) {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'SBOM legal input gate did not pass'; blockerCodes = @('SBOM_INPUT_GATE_BLOCKED'); promotedCandidates = $false; artifacts = @() }
    }
    $bomGate = $results | Where-Object name -eq 'sbom-bom-freshness' | Select-Object -First 1
    if ($null -eq $bomGate -or -not $bomGate.executed -or -not $bomGate.passed) {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'fresh CycloneDX BOM gate did not pass'; promotedCandidates = $false; artifacts = @() }
    }
    $bomPath = Join-Path $root 'ja-app-server-bom.json'
    $bomHash = (Get-FileHash -LiteralPath $bomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($bomHash -ne [string]$bomGate.details.sha256) {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'CycloneDX BOM changed after freshness validation'; promotedCandidates = $false; artifacts = @() }
    }
    $reportPath = Join-Path $root 'dependency-license-report.json'
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'fresh dependency and license report is missing'; promotedCandidates = $false; artifacts = @() }
    }
    $reportFile = Get-Item -LiteralPath $reportPath
    if (-not (Test-FreshTimestamp -LastWriteTimeUtc $reportFile.LastWriteTimeUtc -Since $Since)) {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'dependency and license report is stale'; promotedCandidates = $false; artifacts = @() }
    }
    try {
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        $artifacts = @(Get-ChildItem -LiteralPath $root -File -Filter '*.json' | Where-Object { Test-FreshTimestamp -LastWriteTimeUtc $_.LastWriteTimeUtc -Since $Since } | Sort-Object Name | ForEach-Object {
            [ordered]@{ name = $_.Name; bytes = [int64]$_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
        $codes = @($report.blockers | ForEach-Object { [string]$_.code } | Where-Object { $_ } | Sort-Object -Unique)
        $gate = $results | Where-Object name -eq 'sbom-license' | Select-Object -First 1
        $generated = $null -ne $gate -and $gate.executed -and $gate.passed `
            -and $report.schemaVersion -eq 1 -and $report.status -in @('complete', 'blocked')
        $passed = $generated -and $report.status -eq 'complete' -and $codes.Count -eq 0
        return [ordered]@{ status = if ($passed) { 'passed' } elseif ($null -eq $gate -or -not $gate.executed) { 'blocked' } else { 'failed' }; generationPassed = [bool]$generated; blocker = if ($passed) { $null } elseif ($codes.Count -ne 0) { 'dependency or license review has unresolved blockers' } else { 'SBOM and license gate did not complete successfully' }; blockerCodes = $codes; bom = [ordered]@{ path = 'ja-app-server-bom.json'; sha256 = $bomHash; bytes = [int64](Get-Item -LiteralPath $bomPath).Length }; promotedCandidates = $false; artifacts = $artifacts }
    } catch {
        return [ordered]@{ status = 'blocked'; generationPassed = $false; blocker = 'dependency and license report is malformed'; promotedCandidates = $false; artifacts = @() }
    }
}

# 解析架构检查报告，但不把源码片段复制到汇总摘要中。
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

# 执行后为每个门禁补充明确的 skipped/blocked 布尔值。status 仍是主要结论，这些字段用于区分前置条件阻塞和主动跳过。
function Add-ExplicitGateState {
    foreach ($result in $results) {
        $result['skipped'] = $result.status -eq 'skipped'
        $result['blocked'] = $result.status -eq 'blocked' -or ($result.status -eq 'skipped' -and $result.requested)
    }
}

# 将作者信息、密钥、残留进程和产物大小检查合并为一个可审计的静态门禁。各子结果仍单独可见，汇总失败不会掩盖具体原因。
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

# 执行内部证据门禁，并把抛出的诊断转换为稳定的阻塞代码；任何内部失败都不能绕过最终摘要写入。
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

# 生成带依赖关系的跳过结果，确保每个已请求和可选门禁都有记录，即使较早的构建失败使后续门禁无法安全执行。
function New-SkippedResult {
    param([Parameter(Mandatory)]$Step, [Parameter(Mandatory)][string]$Blocker)

    return [ordered]@{ name = $Step.name; status = 'skipped'; requested = $Step.requested; optional = $Step.optional; executed = $false; passed = $false; blocker = $Blocker; failureType = $null; exitCode = $null; dependsOn = @($Step.dependsOn); startedAt = $null; durationMs = 0; stdout = $null; stderr = $null }
}

# 将完整门禁状态映射到每项产品能力，而不是把证据压缩成单个状态字符串。审查者因此可以区分主动跳过、依赖阻塞和真实失败。
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

# 所有步骤运行结束后才把产物和工具输出汇总为精简摘要；测试数量不使用硬编码，当前语料和产物身份仍可供审查。
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
    $sbomVerificationPassed = $sbomEvidence.status -eq 'passed' -or ([bool]$LocalVerification -and $sbomEvidence.generationPassed)
    $derivedEvidencePassed = $contractEvidence.status -eq 'passed' -and $mavenEvidence.status -eq 'passed' -and $vitestEvidence.status -eq 'passed' -and $architectureEvidence.status -eq 'passed' -and $sbomVerificationPassed -and $nativeSmokeEvidence.status -in @('passed', 'skipped') -and @($rustEvidence | Where-Object { $_.status -ne 'passed' }).Count -eq 0 -and @($artifacts | Where-Object { $_.required -and $_.status -ne 'passed' }).Count -eq 0
    return [ordered]@{
        schemaVersion = 2
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        startedAt = $StartedAt.ToString('o')
        repository = 'ja'
        repositoryRoot = '<repo>'
        verificationPurpose = if ($LocalVerification) { 'local-implementation' } else { 'release-readiness' }
        releaseApproval = $false
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
            httpSse = Get-CapabilityGateState -Names @('java-jvm', 'real-provider')
            shutdown = Get-CapabilityGateState -Names @('java-jvm', 'kernel-loop-smoke', 'java-native-smoke', 'windows-webview2')
            native = Get-CapabilityGateState -Names @('native-no-fallback-policy', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke')
            desktop = Get-CapabilityGateState -Names @('windows-webview2')
            runtimeRefresh = Get-CapabilityGateState -Names @('runtime-refresh-windows-webview2')
            interactionPlan = Get-CapabilityGateState -Names @('native-no-fallback-policy', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke', 'interaction-plan-sidecar-stage', 'interaction-plan-windows-webview2')
            turnChangeReview = Get-CapabilityGateState -Names @('native-no-fallback-policy', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke', 'turn-change-review-sidecar-stage', 'turn-change-review-windows-webview2')
            providers = Get-CapabilityGateState -Names @('provider-preflight', 'real-provider')
            runtimeSoak120Minutes = Get-CapabilityGateState -Names @('runtime-soak')
        }
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false; freshnessReference = $StartedAt.ToString('o') }
    }
}

# 即使准备阶段或门禁失败，也要写入摘要；写入器本身不包含异常消息，因为 PowerShell 工具错误可能含有路径、命令行或回显凭据。
function Write-VerificationSummary {
    param([Parameter(Mandatory)]$Summary)

    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $summaryPath = Join-Path $evidenceRoot 'summary.json'
    [System.IO.File]::WriteAllText($summaryPath, ($Summary | ConvertTo-Json -Depth 16) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

# 预先建立完整阶段清单，包括所有 Reactor 项目，以及 Maven 依赖分析使用的 artifactId 选择器。Plan 验收使用独立证据合同，
# 因此其状态在清单和失败报告中都保持独立。静态版本与 unused 检查先于编译；Rust 测试覆盖 workspace 中全部四个 crate 的外置目标。
function Initialize-VerificationSteps {
    Add-VerificationStep -Name 'evidence-freshness' -Requested $true -Kind internal
    Add-VerificationStep -Name 'java-25-preflight' -Requested $true -Kind internal
    Add-VerificationStep -Name 'java-architecture' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/check-java-architecture.ps1', '-OutputPath', (Join-Path $evidenceRoot 'java-architecture.json'))
    Add-VerificationStep -Name 'product-version' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('version:check')
    Add-VerificationStep -Name 'typescript-unused' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('check:unused')
    Add-VerificationStep -Name 'contract' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'tests/contract/run.ps1') -Environment $commonEnvironment
    Add-VerificationStep -Name 'java-jvm' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'ja-agent/pom.xml', ('-Dja.build.directory=' + $javaModuleBuildDirectory), ('-Dja.app-server.build.directory=' + $javaTestTargetDirectory), '-Dja.example.browser=true', 'verify') -Environment $commonEnvironment
    Add-VerificationStep -Name 'java-artifact-package' -Requested $true -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/package-java-app-server.ps1', '-RepositoryRoot', $repositoryRoot, '-OutputDirectory', $javaArtifactDirectory) -Environment $commonEnvironment -DependsOn @('java-jvm')
    Add-VerificationStep -Name 'kernel-loop-smoke' -Requested $true -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/kernel-loop-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_KERNEL_LOOP_CAPTURE = '' }) -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'rust-fmt' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('fmt', '--all', '--', '--check') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-clippy' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('clippy', '--workspace', '--all-targets', '--locked', '--', '-D', 'warnings') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-runtime-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja-runtime', '--all-targets', '--locked') -Environment $commonEnvironment -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'rust-tauri-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja-desktop', '--all-targets', '--locked') -Environment $commonEnvironment -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'rust-cli-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja-cli', '--all-targets', '--locked') -Environment $commonEnvironment -DependsOn @('java-artifact-package')
    Add-VerificationStep -Name 'rust-review-tests' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('test', '-p', 'ja-review', '--all-targets', '--locked') -Environment $commonEnvironment
    Add-VerificationStep -Name 'typescript-typecheck' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('typecheck')
    Add-VerificationStep -Name 'typescript-lint' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('lint')
    Add-VerificationStep -Name 'typescript-tests' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('test', '--reporter=json', '--outputFile', (Join-Path $evidenceRoot 'vitest.json'))
    Add-VerificationStep -Name 'desktop-build' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('build') -DependsOn @('typescript-typecheck')
    Add-VerificationStep -Name 'workspace-architecture' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('check:architecture')
    $mavenDependencySelectors = ':ja-agent-core,:ja-agent-store-local,:ja-agent-store-pg,:ja-agent-skills-local,:ja-agent-skills-pg,:ja-app-server,:ja-agent-example-springboot'
    Add-VerificationStep -Name 'maven-dependency-tree' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'ja-agent/pom.xml', '-pl', $mavenDependencySelectors, '-am', 'dependency:tree', '-Dverbose') -Environment $commonEnvironment
    Add-VerificationStep -Name 'maven-dependency-convergence' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'ja-agent/pom.xml', '-pl', $mavenDependencySelectors, '-am', 'enforcer:enforce') -Environment $commonEnvironment
    Add-VerificationStep -Name 'rust-dependency-tree' -Requested $true -Kind command -Command 'cargo.exe' -Arguments @('tree', '--workspace', '--locked', '--all-features', '--duplicates') -Environment $commonEnvironment
    Add-VerificationStep -Name 'node-dependency-tree' -Requested $true -Kind command -Command 'pnpm.cmd' -Arguments @('list', '--depth', '1', '--json')
    Add-VerificationStep -Name 'sbom-bom' -Requested $true -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'ja-agent/pom.xml', 'org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom', '-DskipTests', '-DoutputFormat=json', '-DoutputName=ja-app-server-bom', ('-DoutputDirectory=' + (Join-Path $evidenceRoot 'sbom')), '-DoutputReactorProjects=false', '-DexcludeTestProject') -Environment $commonEnvironment
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
    # LocalVerification 仍生成完整发布阻塞报告，并验证真实源码/产物输入；只解除发布批准对 Native 代码验收的依赖。
    if (-not $LocalVerification) { $sbomLicenseArguments.Add('-FailOnBlocker') }
    Add-VerificationStep -Name 'sbom-license' -Requested $true -Kind command -Command 'pwsh' -Arguments @($sbomLicenseArguments) -PersistOutput $false -DependsOn @('sbom-bom-freshness', 'sbom-inputs') -RequiredInputs @('CorrespondingSourcePath', 'ArtifactPath')
    Add-VerificationStep -Name 'git-diff-check' -Requested $true -Kind command -Command 'git.exe' -Arguments @('diff', '--check', '--')
    Add-VerificationStep -Name 'native-no-fallback-policy' -Requested $nativeRequested -Kind internal -Optional $true
    # JVM Reactor 与打包使用唯一目标目录，Native Image 再执行 clean。将它放在所有已生成的合同、JVM、Host、smoke 和 SBOM/许可证阻断门禁之后，
    # 避免前置条件失败仍消耗一次性 Windows 构建机会，或留下会误导后续门禁的可执行文件。
    Add-VerificationStep -Name 'java-native-build' -Requested $nativeRequested -Kind command -Command 'mvn.cmd' -Arguments @('-B', '-ntp', '-f', 'ja-agent/pom.xml', '-pl', ':ja-app-server', '-am', '-Pnative', '-DskipTests', 'clean', 'package') -Environment $commonEnvironment -DependsOn @('java-25-preflight', 'java-architecture', 'contract', 'java-jvm', 'rust-tauri-tests', 'kernel-loop-smoke', 'sbom-bom-freshness', 'sbom-inputs', 'sbom-license', 'native-no-fallback-policy') -Optional $true
    Add-VerificationStep -Name 'native-artifact-freshness' -Requested $nativeRequested -Kind internal -DependsOn @('java-native-build') -Optional $true
    Add-VerificationStep -Name 'java-native-smoke' -Requested $nativeRequested -Kind command -Command 'python.exe' -Arguments @('scripts/native/run-sidecar-smoke.py', '--executable', (Join-Path $repositoryRoot 'ja-agent/ja-app-server/target/ja-app-server.exe'), '--data-dir', (Join-Path $evidenceRoot 'native-data'), '--output', (Join-Path $evidenceRoot 'native-smoke.json')) -DependsOn @('native-artifact-freshness') -Optional $true
    Add-VerificationStep -Name 'runtime-soak' -Requested ([bool]$IncludeSoak) -Kind command -Command 'pwsh' -Arguments @('-NoProfile', '-File', 'scripts/verification/run-runtime-soak.ps1', '-EvidenceDirectory', (Join-Path $evidenceRoot 'runtime-soak'), '-Executable', (Join-Path $repositoryRoot 'ja-agent/ja-app-server/target/ja-app-server.exe'), '-DurationMinutes', [string]$SoakDurationMinutes) -DependsOn @('java-native-smoke') -Optional $true
    Add-VerificationStep -Name 'windows-webview2' -Requested ([bool]$IncludeDesktop) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/windows-desktop-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_E2E_REAL_PROVIDER = '0'; JA_E2E_REAL_PROVIDER_API_KEY = ''; JA_E2E_KEEP_TEMP = '0'; JA_E2E_SCREENSHOT_DIR = (Join-Path $evidenceRoot 'windows-webview2') }) -DependsOn @('java-artifact-package', 'kernel-loop-smoke', 'rust-tauri-tests', 'typescript-tests', 'desktop-build') -PersistOutput $false -Optional $true
    # 请求级运行环境更新必须独立覆盖跨 API continuation、MCP list_changed 与旧 batch 绑定；
    # 固定关闭外部 Provider，真实三次请求全部由 runner 内的 loopback fixture 提供。
    Add-VerificationStep -Name 'runtime-refresh-windows-webview2' -Requested ([bool]$IncludeRuntimeRefresh) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/windows-desktop-smoke.mjs') -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_E2E_RUNTIME_REFRESH_ONLY = '1'; JA_E2E_REAL_PROVIDER = '0'; JA_E2E_REAL_PROVIDER_API_KEY = ''; JA_E2E_KEEP_TEMP = '0'; JA_E2E_SCREENSHOT_DIR = (Join-Path $evidenceRoot 'runtime-refresh-webview2') }) -DependsOn @('java-artifact-package', 'kernel-loop-smoke', 'rust-tauri-tests', 'typescript-tests', 'desktop-build') -PersistOutput $false -Optional $true
    # 独立 Plan runner 同时验证真窗工作流、恢复和长稳；先暂存本轮 Native Image，避免复用
    # 普通桌面 smoke 的 JVM/JAR 路径或将通用工作台通过误报为 Plan 验收。
    Add-VerificationStep -Name 'interaction-plan-sidecar-stage' -Requested ([bool]$IncludePlan) -Kind command -Command 'python.exe' -Arguments @('scripts/native/stage-sidecar.py', '--artifact', (Join-Path $repositoryRoot 'ja-agent/ja-app-server/target/ja-app-server.exe'), '--output-dir', $interactionPlanSidecarDirectory, '--target-triple', $turnChangeReviewTargetTriple, '--platform', 'windows', '--arch', 'x86_64', '--source-commit', $turnChangeReviewSourceCommit) -DependsOn @('java-native-smoke') -Optional $true
    Add-VerificationStep -Name 'interaction-plan-windows-webview2' -Requested ([bool]$IncludePlan) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/interaction-plan-production.mjs', '--evidence-directory', (Join-Path $evidenceRoot 'interaction-plan-webview2'), '--sidecar-directory', $interactionPlanSidecarDirectory, '--soak-minutes', [string]$PlanSoakDurationMinutes) -DependsOn @('interaction-plan-sidecar-stage', 'java-artifact-package', 'kernel-loop-smoke', 'rust-tauri-tests', 'typescript-tests', 'desktop-build') -PersistOutput $false -Optional $true
    # Turn Change Review 先把本轮 Native Image 复制为 Tauri target-triple sidecar，并由 staging
    # manifest 锁定源/目标哈希；桌面验收不得直接使用 JVM JAR 或未绑定身份的旧 executable。
    Add-VerificationStep -Name 'turn-change-review-sidecar-stage' -Requested ([bool]$IncludeTurnChangeReview) -Kind command -Command 'python.exe' -Arguments @('scripts/native/stage-sidecar.py', '--artifact', (Join-Path $repositoryRoot 'ja-agent/ja-app-server/target/ja-app-server.exe'), '--output-dir', $turnChangeReviewSidecarDirectory, '--target-triple', $turnChangeReviewTargetTriple, '--platform', 'windows', '--arch', 'x86_64', '--source-commit', $turnChangeReviewSourceCommit) -DependsOn @('java-native-smoke') -Optional $true
    # 该入口拥有独立 `_ONLY` 模式和严格 JSON 证据合同；产品 hook 未就绪时 exit 2 明确
    # blocked，不能复用通用 desktop 或 Interaction/Plan 的通过状态。
    Add-VerificationStep -Name 'turn-change-review-windows-webview2' -Requested ([bool]$IncludeTurnChangeReview) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/turn-change-review-production.mjs', '--evidence-directory', (Join-Path $evidenceRoot 'turn-change-review-webview2'), '--sidecar-directory', $turnChangeReviewSidecarDirectory) -Environment (Merge-Environment -Base $commonEnvironment -Overrides @{ JA_E2E_REAL_PROVIDER = '0'; JA_E2E_REAL_PROVIDER_API_KEY = ''; JA_E2E_APP_SERVER_JAR = ''; JA_E2E_KEEP_TEMP = '0' }) -DependsOn @('turn-change-review-sidecar-stage', 'java-native-smoke', 'desktop-build', 'rust-tauri-tests', 'typescript-tests') -PersistOutput $false -Optional $true
    Add-VerificationStep -Name 'provider-preflight' -Requested ([bool]$IncludeRealProvider) -Kind internal -Optional $true
    Add-VerificationStep -Name 'real-provider' -Requested ([bool]$IncludeRealProvider) -Kind command -Command 'node.exe' -Arguments @('scripts/e2e/real-provider-smoke.mjs') -Environment $commonEnvironment -DependsOn @('provider-preflight', 'java-artifact-package', 'kernel-loop-smoke') -PersistOutput $false -Optional $true
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

# 非空证据目录是不可变输入，不能用阻塞报告覆盖。将完整门禁清单写到 stdout，并保持其中原有字节不变。
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
        verificationPurpose = if ($LocalVerification) { 'local-implementation' } else { 'release-readiness' }
        releaseApproval = $false
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
        if ($javaHomeIssue -and $step.name -in @('java-jvm', 'java-artifact-package', 'maven-dependency-tree', 'maven-dependency-convergence', 'kernel-loop-smoke', 'java-native-build', 'native-artifact-freshness', 'java-native-smoke', 'runtime-soak', 'windows-webview2', 'runtime-refresh-windows-webview2', 'interaction-plan-sidecar-stage', 'interaction-plan-windows-webview2', 'turn-change-review-sidecar-stage', 'turn-change-review-windows-webview2', 'real-provider')) {
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
                # 在下一个依赖步骤运行前创建快照：打包有意使用干净的隔离输出，可能会在此后删除共享 target 中的报告。
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
            if ($LocalVerification -and $step.name -eq 'sbom-license') {
                # 非严格生成器 exit 0 不等于证据完整；在 Native 可消费此依赖前验证本轮报告、BOM 哈希和真实输入。
                $sbomGeneration = Get-SbomLicenseEvidence -Since $runStartedAt
                $commandResult['sbomGeneration'] = $sbomGeneration
                if (-not $sbomGeneration.generationPassed) {
                    $commandResult['passed'] = $false
                    $commandResult['status'] = 'failed'
                    $commandResult['blocker'] = 'SBOM evidence generation or input validation failed'
                }
            }
        }
    }
} catch {
    # 保留稳定的阻塞项并继续写入摘要；保存前有意对异常文本脱敏，使命令封装回归仍可追踪，
    # 同时不持久化主机路径、子进程输出或凭据。
    $results.Add([ordered]@{ name = 'verification-runner'; status = 'failed'; requested = $true; executed = $false; passed = $false; blocker = 'runner-exception'; failureType = $_.Exception.GetType().Name; failureCode = ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.FullyQualifiedErrorId; exitCode = 1; startedAt = $null; durationMs = 0; stdout = $null; stderr = $null })
    $results[$results.Count - 1]['failureSite'] = ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.InvocationInfo.PositionMessage
    $results[$results.Count - 1]['failureMessage'] = ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.Exception.Message
}

$summary = $null
try {
    $summary = New-VerificationSummary -StartedAt $runStartedAt
} catch {
    # 摘要补充信息生成失败时，仍保留机器可读的失败记录；诊断在保存前脱敏，便于修复 runner，同时不持久化主机路径、命令或密钥。
    $summary = [ordered]@{
        schemaVersion = 2
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        startedAt = $runStartedAt.ToString('o')
        repository = 'ja'
        repositoryRoot = '<repo>'
        verificationPurpose = if ($LocalVerification) { 'local-implementation' } else { 'release-readiness' }
        releaseApproval = $false
        evidenceDirectory = Get-SafePath -Path $evidenceRoot
        passed = $false
        requestedSteps = @($results | Where-Object requested).Count
        executedSteps = @($results | Where-Object executed).Count
        skippedSteps = @($results | Where-Object { $_.status -eq 'skipped' }).Count
        failedSteps = 1
        results = @($results)
        blocker = 'summary-generation-failed'
        failureType = $_.Exception.GetType().Name
        failureCode = ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.FullyQualifiedErrorId
        failureMessage = ConvertTo-SafeDiagnosticText -RepositoryRoot $repositoryRoot -Value $_.Exception.Message
        verificationPolicy = [ordered]@{ cwd = 'repository-root'; rawCommandOutputPersisted = $false; providerOutputPersistedInEvidence = $false; providerTemporaryRuntimeDeletedBySmoke = $true; credentialsInEvidence = $false }
    }
}
Write-VerificationSummary -Summary $summary
$summary | ConvertTo-Json -Depth 16
if (-not $summary.passed) { exit 1 }
