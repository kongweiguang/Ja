# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$ScratchDirectory = '.tmp/verification/production-script-selftest'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\diagnostics.ps1')

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scratchRoot = [System.IO.Path]::GetFullPath($(if ([System.IO.Path]::IsPathRooted($ScratchDirectory)) {
    $ScratchDirectory
} else {
    Join-Path $repositoryRoot $ScratchDirectory
}))
$allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot '.tmp\verification')).TrimEnd('\') + '\'
if (-not ($scratchRoot.TrimEnd('\') + '\').StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'verification self-test scratch directory must stay under .tmp/verification'
}
if (Test-Path -LiteralPath $scratchRoot) {
    throw 'verification self-test requires a fresh scratch directory'
}
[System.IO.Directory]::CreateDirectory($scratchRoot) | Out-Null

# 以稳定名称标记单项不变量失败，便于追踪 runner 回归。
function Assert-VerificationInvariant {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Name)

    if (-not $Condition) { throw "verification self-test failed: $Name" }
}

# 在仓库外执行 ListOnly，以检查门禁依赖及本地/发布用途边界而不生成证据。
function Get-ListOnlyInventory {
    param(
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [switch]$AllOptional,
        [switch]$IncludePlan,
        [switch]$LocalVerification,
        [string]$CorrespondingSourcePath = '',
        [string[]]$ArtifactPath = @()
    )

    $arguments = @('-NoProfile', '-File', (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1'),
        '-EvidenceDirectory', $EvidenceDirectory, '-ListOnly')
    if ($AllOptional) { $arguments += @('-IncludeNative', '-IncludeSoak', '-IncludeDesktop', '-IncludeRuntimeRefresh', '-IncludePlan', '-IncludeTurnChangeReview', '-IncludeRealProvider') }
    elseif ($IncludePlan) { $arguments += '-IncludePlan' }
    if ($LocalVerification) { $arguments += '-LocalVerification' }
    if (-not [string]::IsNullOrWhiteSpace($CorrespondingSourcePath)) { $arguments += @('-CorrespondingSourcePath', $CorrespondingSourcePath) }
    foreach ($artifact in @($ArtifactPath)) { $arguments += @('-ArtifactPath', $artifact) }
    Push-Location $scratchRoot
    try {
        $json = & pwsh @arguments | Out-String
        Assert-VerificationInvariant -Condition ($LASTEXITCODE -eq 0) -Name 'list-only-exit'
        return @($json | ConvertFrom-Json)
    } finally {
        Pop-Location
    }
}

# 通过真实命令边界和下一枚子进程观察环境，覆盖正常、非零退出及启动失败，避免只复刻 finally 的实现。
function Invoke-CommandEnvironmentRegression {
    param([Parameter(Mandatory)][string]$VerificationScript, [Parameter(Mandatory)][string]$FixtureDirectory)

    $ast = [Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$null, [ref]$null)
    foreach ($name in @('Write-SanitizedCommandOutput', 'Invoke-CommandGate')) {
        $definition = $ast.Find({
            param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        }, $false)
        if ($null -eq $definition) { throw "environment fixture function is missing: $name" }
        . ([ScriptBlock]::Create($definition.Extent.Text))
    }
    $repositoryRoot = $FixtureDirectory
    $evidenceRoot = Join-Path $FixtureDirectory 'evidence'
    [IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $probePath = Join-Path $FixtureDirectory 'environment-probe.ps1'
    [IO.File]::WriteAllText($probePath, @'
# @author kongweiguang
param([string]$Name, [int]$ExitCode = 0)
$value = [Environment]::GetEnvironmentVariable($Name, 'Process')
[Console]::Out.WriteLine((@{ exists = $null -ne $value; value = $value } | ConvertTo-Json -Compress))
exit $ExitCode
'@, [Text.UTF8Encoding]::new($false))
    $variable = 'CARGO_TARGET_DIR'
    $original = [Environment]::GetEnvironmentVariable($variable, 'Process')
    $cases = @()
    try {
        foreach ($state in @('missing', 'empty', 'value')) {
            foreach ($mode in @('success', 'nonzero', 'unavailable')) {
                if ($state -eq 'missing') {
                    [Environment]::SetEnvironmentVariable($variable, [System.Management.Automation.Language.NullString]::Value, 'Process')
                } else {
                    [Environment]::SetEnvironmentVariable($variable, $(if ($state -eq 'empty') { '' } else { 'original-target-value' }), 'Process')
                }
                $expected = [Environment]::GetEnvironmentVariable($variable, 'Process')
                $stepName = 'environment-' + $state + '-' + $mode
                $step = [ordered]@{
                    name = $stepName; command = if ($mode -eq 'unavailable') { 'ja-missing-command-' + [guid]::NewGuid().ToString('N') } else { 'pwsh' }
                    arguments = @('-NoProfile', '-File', $probePath, '-Name', $variable, '-ExitCode', $(if ($mode -eq 'nonzero') { '7' } else { '0' }))
                    environment = @{ CARGO_TARGET_DIR = 'temporary-target-value' }; dependsOn = @(); persistOutput = $true
                    requested = $true; optional = $false
                }
                $result = Invoke-CommandGate -Step $step
                if ($mode -ne 'unavailable') {
                    $during = Get-Content -LiteralPath (Join-Path $evidenceRoot $result.stdout) -Raw | ConvertFrom-Json
                    Assert-VerificationInvariant -Condition ($during.exists -and $during.value -eq 'temporary-target-value') -Name ($stepName + '-child-sees-override')
                    Assert-VerificationInvariant -Condition ($result.exitCode -eq $(if ($mode -eq 'nonzero') { 7 } else { 0 })) -Name ($stepName + '-native-exit')
                } else {
                    Assert-VerificationInvariant -Condition ($result.status -eq 'blocked' -and -not $result.executed) -Name ($stepName + '-start-failure')
                }
                $next = (& pwsh -NoProfile -File $probePath -Name $variable | Out-String) | ConvertFrom-Json
                Assert-VerificationInvariant -Condition ($LASTEXITCODE -eq 0 -and $next.exists -eq ($null -ne $expected) -and $next.value -ceq $expected) -Name ($stepName + '-next-child-sees-original')
                $cases += [ordered]@{ state = $state; mode = $mode; passed = $true }
            }
        }
    } finally {
        if ($null -eq $original) {
            [Environment]::SetEnvironmentVariable($variable, [System.Management.Automation.Language.NullString]::Value, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($variable, $original, 'Process')
        }
    }
    [IO.File]::WriteAllText((Join-Path $FixtureDirectory 'environment-regression.json'), ($cases | ConvertTo-Json) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

$baseEvidence = Join-Path $scratchRoot 'list-base'
$allEvidence = Join-Path $scratchRoot 'list-all'
$planEvidence = Join-Path $scratchRoot 'list-plan-only'
$base = @(Get-ListOnlyInventory -EvidenceDirectory $baseEvidence)
$all = @(Get-ListOnlyInventory -EvidenceDirectory $allEvidence -AllOptional)
$planOnly = @(Get-ListOnlyInventory -EvidenceDirectory $planEvidence -IncludePlan)
Assert-VerificationInvariant -Condition ($base.Count -eq 43 -and @($base | Where-Object requested).Count -eq 30) -Name 'base-inventory-count'
Assert-VerificationInvariant -Condition ($all.Count -eq 43 -and @($all | Where-Object requested).Count -eq 43) -Name 'all-inventory-count'
Assert-VerificationInvariant -Condition ($planOnly.Count -eq 43 `
        -and @($planOnly | Where-Object requested).Count -eq 36 `
        -and ($planOnly | Where-Object name -eq 'java-native-build').requested `
        -and ($planOnly | Where-Object name -eq 'java-native-smoke').requested `
        -and ($planOnly | Where-Object name -eq 'interaction-plan-windows-webview2').requested `
        -and -not ($planOnly | Where-Object name -eq 'runtime-soak').requested `
        -and -not ($planOnly | Where-Object name -eq 'windows-webview2').requested) -Name 'standalone-plan-requests-native-only'
Assert-VerificationInvariant -Condition (-not (Test-Path -LiteralPath $baseEvidence) `
        -and -not (Test-Path -LiteralPath $allEvidence) `
        -and -not (Test-Path -LiteralPath $planEvidence)) -Name 'list-only-no-write'

Invoke-CommandEnvironmentRegression -VerificationScript (Join-Path $repositoryRoot 'scripts/verification/run-production-verification.ps1') -FixtureDirectory (Join-Path $scratchRoot 'environment-fixture')

# 外置测试不是 lib target 的子集；必须覆盖各包全部目标，并阻止启动测试复用历史 JAR。
foreach ($gateName in @('rust-runtime-tests', 'rust-tauri-tests', 'rust-cli-tests')) {
    $rustGate = $base | Where-Object name -eq $gateName | Select-Object -First 1
    Assert-VerificationInvariant -Condition (@($rustGate.arguments) -contains '--all-targets' `
            -and @($rustGate.arguments) -notcontains '--lib' `
            -and @($rustGate.dependsOn) -contains 'java-artifact-package') -Name ($gateName + '-external-targets-and-fresh-jar')
}
$reviewRustGate = $base | Where-Object name -eq 'rust-review-tests' | Select-Object -First 1
Assert-VerificationInvariant -Condition (@($reviewRustGate.arguments) -contains '-p' `
        -and @($reviewRustGate.arguments) -contains 'ja-review' `
        -and @($reviewRustGate.arguments) -contains '--all-targets' `
        -and @($reviewRustGate.dependsOn) -notcontains 'java-artifact-package') -Name 'review-crate-independent-targets'
Assert-VerificationInvariant -Condition (@($base | Where-Object name -eq 'rust-host-integration').Count -eq 0) -Name 'desktop-targets-run-once'
$javaGate = $base | Where-Object name -eq 'java-jvm' | Select-Object -First 1
$versionGate = $base | Where-Object name -eq 'product-version' | Select-Object -First 1
$unusedGate = $base | Where-Object name -eq 'typescript-unused' | Select-Object -First 1
$clippyGate = $base | Where-Object name -eq 'rust-clippy' | Select-Object -First 1
Assert-VerificationInvariant -Condition (@($javaGate.arguments | Where-Object { $_ -match '^-Dja.build.directory=target-ja-verification-[a-f0-9]{32}$' }).Count -eq 1 `
        -and @($javaGate.arguments | Where-Object { $_ -match '^-Dja.app-server.build.directory=.+java-test-target$' }).Count -eq 1 `
        -and @($javaGate.arguments) -contains '-Dja.example.browser=true') -Name 'fresh-java-reactor-targets-and-spring-browser'
Assert-VerificationInvariant -Condition ($versionGate.command -eq 'pnpm.cmd' `
        -and @($versionGate.arguments) -contains 'version:check' `
        -and $unusedGate.command -eq 'pnpm.cmd' `
        -and @($unusedGate.arguments) -contains 'check:unused') -Name 'version-and-unused-static-gates'
Assert-VerificationInvariant -Condition (@($clippyGate.arguments) -contains '--workspace' `
        -and @($clippyGate.arguments) -contains '--all-targets' `
        -and @($clippyGate.arguments) -notcontains '-p') -Name 'clippy-covers-full-workspace'

$nativeBuilds = @($all | Where-Object name -eq 'java-native-build')
$nativeBuild = $nativeBuilds | Select-Object -First 1
$nativePolicy = $all | Where-Object name -eq 'native-no-fallback-policy' | Select-Object -First 1
$nativeSmoke = $all | Where-Object name -eq 'java-native-smoke' | Select-Object -First 1
$soak = $all | Where-Object name -eq 'runtime-soak' | Select-Object -First 1
$desktop = $all | Where-Object name -eq 'windows-webview2' | Select-Object -First 1
$runtimeRefreshDesktop = $all | Where-Object name -eq 'runtime-refresh-windows-webview2' | Select-Object -First 1
$interactionPlanStage = $all | Where-Object name -eq 'interaction-plan-sidecar-stage' | Select-Object -First 1
$interactionPlan = $all | Where-Object name -eq 'interaction-plan-windows-webview2' | Select-Object -First 1
$turnChangeStage = $all | Where-Object name -eq 'turn-change-review-sidecar-stage' | Select-Object -First 1
$turnChangeDesktop = $all | Where-Object name -eq 'turn-change-review-windows-webview2' | Select-Object -First 1
$provider = $all | Where-Object name -eq 'real-provider' | Select-Object -First 1
$typescriptTests = $base | Where-Object name -eq 'typescript-tests' | Select-Object -First 1
$sbomInputs = $all | Where-Object name -eq 'sbom-inputs' | Select-Object -First 1
$sbomLicense = $all | Where-Object name -eq 'sbom-license' | Select-Object -First 1
$nativeCleanIndex = [Array]::IndexOf([object[]]$nativeBuild.arguments, 'clean')
$nativePackageIndex = [Array]::IndexOf([object[]]$nativeBuild.arguments, 'package')
Assert-VerificationInvariant -Condition ($nativeBuilds.Count -eq 1 -and $nativeBuild.command -eq 'mvn.cmd') -Name 'single-windows-native-build'
Assert-VerificationInvariant -Condition (@($nativeBuild.arguments | Where-Object { $_ -eq 'clean' }).Count -eq 1 `
        -and @($nativeBuild.arguments | Where-Object { $_ -eq 'package' }).Count -eq 1 `
        -and $nativeCleanIndex -ge 0 -and $nativePackageIndex -gt $nativeCleanIndex) -Name 'native-clean-before-package'
Assert-VerificationInvariant -Condition ($nativePolicy.kind -eq 'internal' `
        -and @($nativeBuild.arguments | Where-Object { $_ -eq '-Pnative' }).Count -eq 1 `
        -and @($nativeBuild.dependsOn) -contains 'native-no-fallback-policy') -Name 'native-policy-dependency'
Assert-VerificationInvariant -Condition (@($nativeBuild.dependsOn) -contains 'contract' `
        -and @($nativeBuild.dependsOn) -contains 'java-jvm' `
        -and @($nativeBuild.dependsOn) -contains 'rust-tauri-tests' `
        -and @($nativeBuild.dependsOn) -contains 'kernel-loop-smoke' `
        -and @($nativeBuild.dependsOn) -contains 'sbom-license') -Name 'native-stop-ship-dependencies'
Assert-VerificationInvariant -Condition (@($nativeSmoke.dependsOn) -contains 'native-artifact-freshness') -Name 'native-smoke-freshness-dependency'
Assert-VerificationInvariant -Condition (@($soak.dependsOn) -contains 'java-native-smoke') -Name 'soak-smoke-dependency'
Assert-VerificationInvariant -Condition (@($desktop.dependsOn) -contains 'desktop-build' -and -not $desktop.persistOutput) -Name 'desktop-runtime-dependency'
Assert-VerificationInvariant -Condition ($runtimeRefreshDesktop.command -eq 'node.exe' `
        -and @($runtimeRefreshDesktop.arguments) -contains 'scripts/e2e/windows-desktop-smoke.mjs' `
        -and @($runtimeRefreshDesktop.dependsOn) -contains 'java-artifact-package' `
        -and @($runtimeRefreshDesktop.dependsOn) -contains 'kernel-loop-smoke' `
        -and @($runtimeRefreshDesktop.dependsOn) -contains 'rust-tauri-tests' `
        -and @($runtimeRefreshDesktop.dependsOn) -contains 'typescript-tests' `
        -and @($runtimeRefreshDesktop.dependsOn) -contains 'desktop-build' `
        -and -not $runtimeRefreshDesktop.persistOutput) -Name 'runtime-refresh-independent-webview2-gate'
Assert-VerificationInvariant -Condition ($interactionPlanStage.command -eq 'python.exe' `
        -and @($interactionPlanStage.arguments) -contains 'scripts/native/stage-sidecar.py' `
        -and @($interactionPlanStage.dependsOn) -contains 'java-native-smoke') -Name 'interaction-plan-native-sidecar-stage'
Assert-VerificationInvariant -Condition ($interactionPlan.command -eq 'node.exe' `
        -and @($interactionPlan.arguments) -contains 'scripts/e2e/interaction-plan-production.mjs' `
        -and @($interactionPlan.arguments) -contains '--evidence-directory' `
        -and @($interactionPlan.arguments) -contains '--sidecar-directory' `
        -and @($interactionPlan.arguments) -contains '--soak-minutes' `
        -and @($interactionPlan.dependsOn) -contains 'interaction-plan-sidecar-stage' `
        -and @($interactionPlan.dependsOn) -contains 'desktop-build' `
        -and -not $interactionPlan.persistOutput) -Name 'standalone-interaction-plan-production-gate'
Assert-VerificationInvariant -Condition ($turnChangeStage.command -eq 'python.exe' `
        -and @($turnChangeStage.arguments) -contains 'scripts/native/stage-sidecar.py' `
        -and @($turnChangeStage.arguments) -contains '--target-triple' `
        -and @($turnChangeStage.arguments) -contains 'x86_64-pc-windows-msvc' `
        -and @($turnChangeStage.dependsOn) -contains 'java-native-smoke') -Name 'turn-change-native-sidecar-stage'
Assert-VerificationInvariant -Condition ($turnChangeDesktop.command -eq 'node.exe' `
        -and @($turnChangeDesktop.arguments) -contains 'scripts/e2e/turn-change-review-production.mjs' `
        -and @($turnChangeDesktop.dependsOn) -contains 'turn-change-review-sidecar-stage' `
        -and @($turnChangeDesktop.dependsOn) -contains 'desktop-build' `
        -and -not $turnChangeDesktop.persistOutput) -Name 'turn-change-independent-webview2-gate'
Assert-VerificationInvariant -Condition (@($provider.dependsOn) -contains 'provider-preflight' -and @($provider.dependsOn) -contains 'kernel-loop-smoke' -and -not $provider.persistOutput) -Name 'provider-runtime-dependency'
Assert-VerificationInvariant -Condition (@($typescriptTests.arguments) -notcontains '--' `
        -and @($typescriptTests.arguments) -contains '--reporter=json' `
        -and @($typescriptTests.arguments) -contains '--outputFile') -Name 'vitest-json-arguments-forwarded'
Assert-VerificationInvariant -Condition ($sbomInputs.kind -eq 'internal' `
        -and @($sbomInputs.requiredInputs | Where-Object { $_ -match 'CorrespondingSourcePath.*absolute.*fresh.*repository-scoped' }).Count -eq 1 `
        -and @($sbomInputs.requiredInputs | Where-Object { $_ -match 'ArtifactPath.*absolute.*fresh.*repository-scoped' }).Count -eq 1) -Name 'sbom-input-requirements-visible'
Assert-VerificationInvariant -Condition (@($sbomLicense.dependsOn) -contains 'sbom-inputs' `
        -and (@($all | Where-Object name -eq 'java-native-build' | Select-Object -First 1).dependsOn) -contains 'sbom-license') -Name 'sbom-native-dependency-chain'

$secret = 'sk-' + 'verification-selftest-' + [Guid]::NewGuid().ToString('N')
$priorSecrets = @{
    JA_REAL_PROVIDER_API_KEY = $env:JA_REAL_PROVIDER_API_KEY
}
try {
    $env:JA_REAL_PROVIDER_API_KEY = $secret
    $secretInventory = @(Get-ListOnlyInventory -EvidenceDirectory (Join-Path $scratchRoot 'list-secret') -AllOptional)
    Assert-VerificationInvariant -Condition (-not (($secretInventory | ConvertTo-Json -Depth 8).Contains($secret))) -Name 'list-only-secret-redaction'
} finally {
    foreach ($name in $priorSecrets.Keys) { Set-Item -Path "Env:$name" -Value $priorSecrets[$name] }
}

$staleEvidence = Join-Path $scratchRoot 'stale-evidence'
[System.IO.Directory]::CreateDirectory($staleEvidence) | Out-Null
$marker = Join-Path $staleEvidence 'marker.txt'
[System.IO.File]::WriteAllText($marker, 'immutable', [System.Text.UTF8Encoding]::new($false))
$staleOutput = & pwsh -NoProfile -File (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') -EvidenceDirectory $staleEvidence 2>&1 | Out-String
Assert-VerificationInvariant -Condition ($LASTEXITCODE -ne 0 -and (Get-Content -LiteralPath $marker -Raw) -eq 'immutable') -Name 'stale-evidence-block'
Assert-VerificationInvariant -Condition (-not (Test-Path -LiteralPath (Join-Path $staleEvidence 'summary.json'))) -Name 'stale-evidence-no-overwrite'
$staleSummary = $staleOutput | ConvertFrom-Json
Assert-VerificationInvariant -Condition ($staleSummary.blocker -eq 'evidence-directory-not-fresh' -and -not $staleSummary.evidenceWritten) -Name 'stale-evidence-semantics'
Assert-VerificationInvariant -Condition (@($staleSummary.results).Count -eq 43 `
        -and @($staleSummary.results | Where-Object { $null -eq $_.PSObject.Properties['requested'] -or $null -eq $_.PSObject.Properties['executed'] -or $null -eq $_.PSObject.Properties['blocked'] }).Count -eq 0) -Name 'requested-executed-blocked-shape'
Assert-VerificationInvariant -Condition (@($staleSummary.results | Where-Object { -not $_.requested -and $_.passed }).Count -eq 0) -Name 'nonrequested-is-not-passed'

# 使用真实结果行执行生产 Cargo 摘要解析器。此用例专门保护类型化结果列表，避免被 PowerShell 不区分大小写的自动变量 `$Matches` 覆盖。
$rustSummaryEvidence = @(& {
    param(
        [Parameter(Mandatory)][string]$VerificationScript,
        [Parameter(Mandatory)][string]$FixtureDirectory
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification Rust summary fixture could not parse runner' }
    $definition = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-RustTestEvidence'
        }, $true)
    if ($null -eq $definition) { throw 'verification Rust summary fixture function is missing' }
    Invoke-Expression $definition.Extent.Text

    [System.IO.Directory]::CreateDirectory($FixtureDirectory) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $FixtureDirectory 'rust.log'),
        'test result: ok. 47 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out',
        [System.Text.UTF8Encoding]::new($false))
    $evidenceRoot = $FixtureDirectory
    $results = [System.Collections.Generic.List[object]]::new()
    $results.Add([ordered]@{
        name = 'rust-runtime-tests'
        stdout = 'rust.log'
        stderr = $null
        passed = $true
    })
    return @(Get-RustTestEvidence)
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'rust-summary-fixture'))
Assert-VerificationInvariant -Condition ($rustSummaryEvidence.Count -eq 1 `
        -and $rustSummaryEvidence[0].status -eq 'passed' `
        -and $rustSummaryEvidence[0].results.Count -eq 1 `
        -and $rustSummaryEvidence[0].results[0].passed -eq 47 `
        -and $rustSummaryEvidence[0].results[0].ignored -eq 1) -Name 'rust-summary-automatic-matches-isolated'

# 在隔离仓库中验证 Maven 证据。夹具先快照 XML 再删除 target，然后检查过期、未来时间、格式错误、不完整证据、重名文件和截断的控制台输出；
# 这些情形确保摘要不会虚构精确的测试套件数量。
$mavenSummaryEvidence = & {
    param(
        [Parameter(Mandatory)][string]$VerificationScript,
        [Parameter(Mandatory)][string]$FixtureDirectory
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification Maven summary fixture could not parse runner' }
    foreach ($functionName in @('Test-FreshTimestamp', 'Get-MavenSurefireReports', 'Get-MavenSurefireTextEvidence', 'Save-MavenSurefireSnapshot', 'Get-MavenTestEvidence')) {
        $definition = $ast.Find({
                param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
            }, $true)
        if ($null -eq $definition) { throw "verification Maven summary fixture function is missing: $functionName" }
        Invoke-Expression $definition.Extent.Text
    }

    $repositoryRoot = [System.IO.Path]::GetFullPath($FixtureDirectory)
    $evidenceRoot = Join-Path $repositoryRoot 'evidence'
    $freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
    $mavenSurefireSnapshotDirectoryName = 'maven-surefire'
    $mavenSurefireReportMaxBytes = [int64](16 * 1024 * 1024)
    $mavenSurefireSnapshotMaxBytes = [int64](128 * 1024 * 1024)
    $runStartedAt = [DateTimeOffset]::UtcNow.AddMinutes(-1)
    $javaModuleBuildDirectory = 'target-fixture'
    $javaTestTargetDirectory = Join-Path $repositoryRoot 'ja-app-server-target'
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $results = [System.Collections.Generic.List[object]]::new()
    $gate = [ordered]@{
        name = 'java-jvm'
        startedAt = $runStartedAt.AddSeconds(1).ToString('o')
        executed = $true
        passed = $true
        stdout = 'java-jvm.stdout.log'
    }
    $results.Add($gate)
    $snapshotRoot = Join-Path $evidenceRoot 'maven-surefire'
    $reportRoot = Join-Path $javaTestTargetDirectory 'nested\surefire-reports'
    [System.IO.Directory]::CreateDirectory($reportRoot) | Out-Null
    foreach ($entry in @(
            @{ name = 'TEST-fixture-one.xml'; tests = 7; skipped = 1 }
            @{ name = 'TEST-fixture-two.xml'; tests = 5; skipped = 0 }
        )) {
        $path = Join-Path $reportRoot $entry.name
        [System.IO.File]::WriteAllText($path, "<testsuite name=`"fixture`" tests=`"$($entry.tests)`" failures=`"0`" errors=`"0`" skipped=`"$($entry.skipped)`" />", [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::SetLastWriteTimeUtc($path, [DateTime]::UtcNow)
    }
    $coreReportRoot = Join-Path $repositoryRoot 'ja-agent/ja-agent-core\target-fixture\surefire-reports'
    [System.IO.Directory]::CreateDirectory($coreReportRoot) | Out-Null
    $coreReport = Join-Path $coreReportRoot 'TEST-core-module.xml'
    [System.IO.File]::WriteAllText($coreReport, '<testsuite name="core" tests="4" failures="0" errors="0" skipped="0" />', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($coreReport, [DateTime]::UtcNow)
    $springReportRoot = Join-Path $repositoryRoot 'ja-agent/example/ja-agent-example-springboot\target-fixture\surefire-reports'
    [System.IO.Directory]::CreateDirectory($springReportRoot) | Out-Null
    $springReport = Join-Path $springReportRoot 'TEST-example-module.xml'
    [System.IO.File]::WriteAllText($springReport, '<testsuite name="example" tests="2" failures="0" errors="0" skipped="0" />', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($springReport, [DateTime]::UtcNow)
    $staleSource = Join-Path $reportRoot 'TEST-prior-gate.xml'
    [System.IO.File]::WriteAllText($staleSource, '<testsuite name="prior" tests="99" failures="0" errors="0" skipped="0" />', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($staleSource, $runStartedAt.UtcDateTime.AddMinutes(-1))
    $mavenSurefireSnapshot = Save-MavenSurefireSnapshot -GateResult $gate
    Remove-Item -LiteralPath $javaTestTargetDirectory -Recurse -Force
    $snapshot = Get-MavenTestEvidence -Since $runStartedAt

    $snapshotFiles = @(Get-ChildItem -LiteralPath $snapshotRoot -Filter 'TEST-*.xml' -File)
    $stalePath = $snapshotFiles[0].FullName
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, $runStartedAt.UtcDateTime.AddSeconds(-1))
    $stale = Get-MavenTestEvidence -Since $runStartedAt
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, [DateTime]::UtcNow.AddMinutes(3))
    $future = Get-MavenTestEvidence -Since $runStartedAt
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, [DateTime]::UtcNow)
    [System.IO.File]::WriteAllText($stalePath, '<testsuite', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, [DateTime]::UtcNow)
    $malformed = Get-MavenTestEvidence -Since $runStartedAt
    [System.IO.File]::WriteAllText($stalePath, '<testsuite name="fixture" tests="7" failures="0" errors="0" skipped="1" />', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, [DateTime]::UtcNow)
    Remove-Item -LiteralPath $snapshotFiles[1].FullName -Force
    $snapshot.expectedCount = 2
    $partial = Get-MavenTestEvidence -Since $runStartedAt

    $consoleRoot = Join-Path $FixtureDirectory 'console-only'
    $repositoryRoot = [System.IO.Path]::GetFullPath($consoleRoot)
    $evidenceRoot = Join-Path $repositoryRoot 'evidence'
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $stdoutPath = Join-Path $evidenceRoot 'java-jvm.stdout.log'
    [System.IO.File]::WriteAllText($stdoutPath, "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in fixture.OneTest`n[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in fixture.TwoTest`n[INFO] Results:`n[INFO] Tests run: 358, Failures: 0, Errors: 0, Skipped: 0", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetLastWriteTimeUtc($stdoutPath, [DateTime]::UtcNow)
    $mavenSurefireSnapshot = [ordered]@{ status = 'absent'; blocker = $null; expectedCount = 0; copiedCount = 0; sourceReportCount = 0; snapshotDirectory = 'maven-surefire' }
    $console = Get-MavenTestEvidence -Since $runStartedAt

    $duplicateRoot = Join-Path $FixtureDirectory 'duplicate'
    $repositoryRoot = [System.IO.Path]::GetFullPath($duplicateRoot)
    $evidenceRoot = Join-Path $repositoryRoot 'evidence'
    $javaTestTargetDirectory = Join-Path $repositoryRoot 'custom-app-target'
    foreach ($duplicateReports in @(
            (Join-Path $javaTestTargetDirectory 'nested\surefire-reports')
            (Join-Path $repositoryRoot 'ja-agent/ja-app-server\target\nested\surefire-reports')
        )) {
        [System.IO.Directory]::CreateDirectory($duplicateReports) | Out-Null
        $duplicatePath = Join-Path $duplicateReports 'TEST-duplicate.xml'
        [System.IO.File]::WriteAllText($duplicatePath, '<testsuite name="duplicate" tests="1" failures="0" errors="0" skipped="0" />', [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::SetLastWriteTimeUtc($duplicatePath, [DateTime]::UtcNow)
    }
    $mavenSurefireSnapshot = Save-MavenSurefireSnapshot -GateResult $gate
    return [ordered]@{
        snapshot = $snapshot
        stale = $stale
        future = $future
        malformed = $malformed
        partial = $partial
        console = $console
        duplicate = $mavenSurefireSnapshot
    }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'maven-summary-fixture')
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.snapshot.status -eq 'passed' `
        -and $mavenSummaryEvidence.snapshot.source -eq 'surefire-xml-snapshot' `
        -and $mavenSummaryEvidence.snapshot.suites -eq 4 `
        -and $mavenSummaryEvidence.snapshot.tests -eq 18 `
        -and $mavenSummaryEvidence.snapshot.snapshot.copiedCount -eq 4 `
        -and $mavenSummaryEvidence.snapshot.snapshot.sourceReportCount -eq 5) -Name 'maven-snapshot-covers-reactor-and-example-modules'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.stale.status -eq 'blocked' `
        -and $mavenSummaryEvidence.stale.blocker -eq 'Maven Surefire snapshot is stale or future-dated') -Name 'maven-snapshot-stale-blocked'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.future.status -eq 'blocked' `
        -and $mavenSummaryEvidence.future.blocker -eq 'Maven Surefire snapshot is stale or future-dated') -Name 'maven-snapshot-future-blocked'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.malformed.status -eq 'blocked' `
        -and $mavenSummaryEvidence.malformed.blocker -eq 'one fresh Maven Surefire report is malformed') -Name 'maven-snapshot-malformed-blocked'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.partial.status -eq 'blocked' `
        -and $mavenSummaryEvidence.partial.blocker -eq 'Maven Surefire snapshot is incomplete') -Name 'maven-snapshot-partial-blocked'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.console.status -eq 'passed' `
        -and $mavenSummaryEvidence.console.source -eq 'maven-gate-output' `
        -and $null -eq $mavenSummaryEvidence.console.suites `
        -and $mavenSummaryEvidence.console.observedSuites -eq 2 `
        -and $mavenSummaryEvidence.console.tests -eq 358) -Name 'maven-console-truncated-suite-unknown'
Assert-VerificationInvariant -Condition ($mavenSummaryEvidence.duplicate.status -eq 'failed' `
        -and $mavenSummaryEvidence.duplicate.blocker -eq 'Maven Surefire snapshot contains duplicate report basenames') -Name 'maven-snapshot-duplicate-basename-failed'

# 确认构建使用模块相对目录隔离时，各模块 Maven target 都会从人工编写源码扫描范围中排除。
$generatedMavenPaths = & {
    param([Parameter(Mandatory)][string]$VerificationScript)

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification generated-path fixture could not parse runner' }
    $definition = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-GeneratedRepositoryPath'
        }, $true)
    if ($null -eq $definition) { throw 'verification generated-path fixture function is missing' }
    Invoke-Expression $definition.Extent.Text

    return @(
        (Test-GeneratedRepositoryPath 'ja-agent/ja-agent-core/target-build_examples/classes/Agent.class')
        (Test-GeneratedRepositoryPath 'ja-agent/extensions/ja-agent-store-pg/target-store/test-classes/Contract.class')
        (Test-GeneratedRepositoryPath 'ja-agent/example/ja-agent-example-springboot/target-engine/surefire-reports/TEST-demo.xml')
        (Test-GeneratedRepositoryPath 'ja-agent/ja-app-server/target/ja-app-server.jar')
        (Test-GeneratedRepositoryPath 'ja-agent/ja-app-server/src/main/java/Example.java')
    )
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1')
Assert-VerificationInvariant -Condition ($generatedMavenPaths.Count -eq 5 `
        -and $generatedMavenPaths[0] -and $generatedMavenPaths[1] -and $generatedMavenPaths[2] `
        -and $generatedMavenPaths[3] -and -not $generatedMavenPaths[4]) -Name 'isolated-maven-targets-excluded-from-authored-inputs'

# 加载生产脱敏器，验证输出嘈杂的命令仍保留末尾动态结论，同时持久化诊断不会超过固定的首尾预算。
$boundedDiagnostic = & {
    param([Parameter(Mandatory)][string]$DiagnosticsScript)

    . $DiagnosticsScript
    $verdict = 'test result: ok. 266 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out'
    return ConvertTo-SafeDiagnosticText -Value ('startup-marker' + ('x' * 20000) + $verdict) -RepositoryRoot 'C:\verification-fixture-root'
} (Join-Path $repositoryRoot 'scripts\lib\diagnostics.ps1')
Assert-VerificationInvariant -Condition ($boundedDiagnostic.Contains('startup-marker') `
        -and $boundedDiagnostic.Contains('<output-truncated>') `
        -and $boundedDiagnostic.Contains('266 passed; 0 failed') `
        -and $boundedDiagnostic.Length -lt 17000) -Name 'bounded-diagnostic-preserves-head-and-verdict-tail'

# 验证产物预算不会把前置条件失败重复报告为文件过期；生成步骤成功后，同一过期身份仍必须导致硬失败。
$artifactSizeEvidence = & {
    param([Parameter(Mandatory)][string]$VerificationScript)

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification size fixture could not parse runner' }
    $definition = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-SizeGateEvidence'
        }, $true)
    if ($null -eq $definition) { throw 'verification size fixture function is missing' }
    Invoke-Expression $definition.Extent.Text

    $artifact = [ordered]@{ required = $true; exists = $true; freshThisRun = $false; actualBytes = 8; maxBytes = 16; path = 'native.exe'; producedBy = 'native-build' }
    $results = @([ordered]@{ name = 'native-build'; passed = $false })
    $blocked = Get-SizeGateEvidence -Artifacts @($artifact)
    $results = @([ordered]@{ name = 'native-build'; passed = $true })
    $produced = Get-SizeGateEvidence -Artifacts @($artifact)
    return [ordered]@{ blocked = $blocked; produced = $produced }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1')
Assert-VerificationInvariant -Condition ($artifactSizeEvidence.blocked.passed `
        -and -not $artifactSizeEvidence.produced.passed `
        -and $artifactSizeEvidence.produced.findings[0].rule -eq 'artifact-stale') -Name 'artifact-size-prerequisite-attribution'

# 使用证据文本和编译后的 JVM 输出验证生产密钥扫描器。类文件常量池不属于文本泄露面；日志中出现凭据形态的值时，
# 仍须以失败关闭，并且只返回路径、行号和规则元数据。
$secretScanEvidence = & {
    param(
        [Parameter(Mandatory)][string]$VerificationScript,
        [Parameter(Mandatory)][string]$FixtureDirectory
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification secret fixture could not parse runner' }
    foreach ($functionName in @('Test-GeneratedRepositoryPath', 'Get-SafePath', 'Get-SecretGateEvidence')) {
        $definition = $ast.Find({
                param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
            }, $true)
        if ($null -eq $definition) { throw "verification secret fixture function is missing: $functionName" }
        Invoke-Expression $definition.Extent.Text
    }

    $repositoryRoot = Join-Path $FixtureDirectory 'repository'
    $evidenceRoot = Join-Path $repositoryRoot 'evidence'
    [System.IO.Directory]::CreateDirectory($repositoryRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    [System.IO.File]::WriteAllBytes((Join-Path $evidenceRoot 'fixture.class'),
        [System.Text.Encoding]::UTF8.GetBytes('authorization="credential-shaped-compiled-fixture"'))
    [System.IO.File]::WriteAllText((Join-Path $evidenceRoot 'safe.log'),
        'authorization="test-placeholder-value"', [System.Text.UTF8Encoding]::new($false))
    $binaryIgnored = Get-SecretGateEvidence
    $credentialFixture = 'credential-shaped-' + 'evidence-value'
    [System.IO.File]::WriteAllText((Join-Path $evidenceRoot 'leak.log'),
        ('authorization="' + $credentialFixture + '"'), [System.Text.UTF8Encoding]::new($false))
    $textRejected = Get-SecretGateEvidence
    return [ordered]@{ binaryIgnored = $binaryIgnored; textRejected = $textRejected }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'secret-scan-fixture')
Assert-VerificationInvariant -Condition ($secretScanEvidence.binaryIgnored.passed `
        -and -not $secretScanEvidence.textRejected.passed `
        -and $secretScanEvidence.textRejected.findingCount -eq 1 `
        -and $secretScanEvidence.textRejected.findings[0].path -eq 'evidence/leak.log' `
        -and $secretScanEvidence.textRejected.findings[0].rule -eq 'credential-shaped-literal') -Name 'secret-scan-text-binary-boundary'

# 隔离加载 Native smoke 子门禁策略。夹具在不启动 Maven、Native Image 或 sidecar 进程的情况下，
# 分别验证阻塞、缺失和被篡改的报告。
$subgatePolicyEvidence = & {
    param([Parameter(Mandatory)][string]$VerificationScript)

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification subgate fixture could not parse runner' }
    $definition = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-NativeSmokeSubgateEvidence'
        }, $true)
    if ($null -eq $definition) { throw 'verification subgate fixture function is missing' }
    $requiredNativeSmokeSubgates = @('jsonSchema', 'configAuth', 'okhttpSse', 'mcp', 'shellCancellation', 'shellStdinEof', 'sqlite', 'recovery', 'networknt')
    Invoke-Expression $definition.Extent.Text

    $complete = [ordered]@{}
    foreach ($name in $requiredNativeSmokeSubgates) { $complete[$name] = [pscustomobject]@{ status = 'passed'; passed = $true } }
    $completeReport = [pscustomobject]@{ subgates = [pscustomobject]$complete }
    $passed = Get-NativeSmokeSubgateEvidence -Report $completeReport -RequiredSubgates $requiredNativeSmokeSubgates

    $blockedMap = [ordered]@{}; foreach ($entry in $complete.GetEnumerator()) { $blockedMap[$entry.Key] = $entry.Value }
    $blockedMap['mcp'] = [pscustomobject]@{ status = 'blocked'; reason = 'fixture' }
    $blocked = Get-NativeSmokeSubgateEvidence -Report ([pscustomobject]@{ subgates = [pscustomobject]$blockedMap }) -RequiredSubgates $requiredNativeSmokeSubgates

    $missingMap = [ordered]@{}; foreach ($entry in $complete.GetEnumerator()) { $missingMap[$entry.Key] = $entry.Value }
    $missingMap.Remove('networknt')
    $missing = Get-NativeSmokeSubgateEvidence -Report ([pscustomobject]@{ subgates = [pscustomobject]$missingMap }) -RequiredSubgates $requiredNativeSmokeSubgates

    $mutatedMap = [ordered]@{}; foreach ($entry in $complete.GetEnumerator()) { $mutatedMap[$entry.Key] = $entry.Value }
    $mutatedMap['configAuth'] = [pscustomobject]@{ status = 'passed'; passed = $false }
    $mutated = Get-NativeSmokeSubgateEvidence -Report ([pscustomobject]@{ subgates = [pscustomobject]$mutatedMap }) -RequiredSubgates $requiredNativeSmokeSubgates
    return [ordered]@{ passed = $passed; blocked = $blocked; missing = $missing; mutated = $mutated }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1')
Assert-VerificationInvariant -Condition ($subgatePolicyEvidence.passed.passed -and $subgatePolicyEvidence.passed.status -eq 'passed') -Name 'native-required-subgates-positive'
Assert-VerificationInvariant -Condition (-not $subgatePolicyEvidence.blocked.passed `
        -and $subgatePolicyEvidence.blocked.status -eq 'blocked' `
        -and @($subgatePolicyEvidence.blocked.blocked) -contains 'mcp') -Name 'native-required-subgates-blocked'
Assert-VerificationInvariant -Condition (-not $subgatePolicyEvidence.missing.passed `
        -and $subgatePolicyEvidence.missing.status -eq 'blocked' `
        -and @($subgatePolicyEvidence.missing.missing) -contains 'networknt') -Name 'native-required-subgates-missing'
Assert-VerificationInvariant -Condition (-not $subgatePolicyEvidence.mutated.passed `
        -and $subgatePolicyEvidence.mutated.status -eq 'failed' `
        -and @($subgatePolicyEvidence.mutated.failed) -contains 'configAuth') -Name 'native-required-subgates-mutated'

# 仅在隔离作用域加载产物门禁函数，验证 Spring Boot 示例 JAR 与本次 Reactor 校验绑定，且不调用 Maven 或任何运行时 smoke。
$nativeFixtureEvidence = & {
    param(
        [Parameter(Mandatory)][string]$VerificationScript,
        [Parameter(Mandatory)][string]$FixtureRepository
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification artifact fixture could not parse runner' }
    foreach ($functionName in @('Test-FreshTimestamp', 'Get-FileMtimeNanoseconds', 'Get-ArtifactEvidence', 'Get-NativeArtifactFreshnessEvidence')) {
        $definition = $ast.Find({
                param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
            }, $true)
        if ($null -eq $definition) { throw "verification artifact fixture function is missing: $functionName" }
        Invoke-Expression $definition.Extent.Text
    }

    $repositoryRoot = $FixtureRepository
    $nativeRequested = $true
    $freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
    $nativeExecutableMaxBytes = [int64](120 * 1024 * 1024)
    $javaModuleBuildDirectory = 'target-fixture'
    $javaProductVersion = '0.1.21'
    $runStartedAt = [DateTimeOffset]::UtcNow.AddMinutes(-1)
    $results = [System.Collections.Generic.List[object]]::new()
    $results.Add([ordered]@{ name = 'java-jvm'; passed = $true; startedAt = $runStartedAt.ToString('o') })
    $results.Add([ordered]@{ name = 'java-native-build'; passed = $true; startedAt = $runStartedAt.ToString('o') })

    $springExamplePath = Join-Path $repositoryRoot "ja-agent/example/ja-agent-example-springboot/$javaModuleBuildDirectory/ja-agent-example-springboot-$javaProductVersion-exec.jar"
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $springExamplePath)) | Out-Null
    [System.IO.File]::WriteAllBytes($springExamplePath, [byte[]]@(1, 2, 3))
    [System.IO.File]::SetLastWriteTimeUtc($springExamplePath, [DateTime]::UtcNow)
    $exampleArtifacts = @(Get-ArtifactEvidence -Since $runStartedAt | Where-Object key -eq 'springBootExampleJar')

    $nativePath = Join-Path $repositoryRoot 'ja-agent/ja-app-server\target\ja-app-server.exe'
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $nativePath)) | Out-Null
    $stream = [System.IO.File]::Open($nativePath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $stream.SetLength($nativeExecutableMaxBytes)
    } finally {
        $stream.Dispose()
    }
    [System.IO.File]::SetLastWriteTimeUtc($nativePath, [DateTime]::UtcNow)
    $atLimit = Get-NativeArtifactFreshnessEvidence

    $stream = [System.IO.File]::Open($nativePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $stream.SetLength($nativeExecutableMaxBytes + 1)
    } finally {
        $stream.Dispose()
    }
    [System.IO.File]::SetLastWriteTimeUtc($nativePath, [DateTime]::UtcNow)
    $overLimit = Get-NativeArtifactFreshnessEvidence

    $stream = [System.IO.File]::Open($nativePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $stream.SetLength($nativeExecutableMaxBytes)
    } finally {
        $stream.Dispose()
    }
    [System.IO.File]::SetLastWriteTimeUtc($nativePath, $runStartedAt.UtcDateTime.AddSeconds(-1))
    $stale = Get-NativeArtifactFreshnessEvidence

    Remove-Item -LiteralPath $nativePath -Force
    $missing = Get-NativeArtifactFreshnessEvidence
    return [ordered]@{ atLimit = $atLimit; overLimit = $overLimit; stale = $stale; missing = $missing; examples = $exampleArtifacts }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'native-artifact-fixture')

Assert-VerificationInvariant -Condition ($nativeFixtureEvidence.examples.Count -eq 1 `
        -and @($nativeFixtureEvidence.examples | Where-Object { -not $_.passed -or -not $_.freshThisRun }).Count -eq 0) -Name 'example-jars-are-current-reactor-artifacts'

Assert-VerificationInvariant -Condition ($nativeFixtureEvidence.atLimit.passed `
        -and $nativeFixtureEvidence.atLimit.artifact.actualBytes -eq 125829120 `
        -and $nativeFixtureEvidence.atLimit.artifact.maxBytes -eq 125829120 `
        -and $nativeFixtureEvidence.atLimit.artifact.mtimeNs -gt 0 `
        -and $nativeFixtureEvidence.atLimit.artifact.passed) -Name 'native-size-at-approved-limit-passes'
Assert-VerificationInvariant -Condition (-not $nativeFixtureEvidence.overLimit.passed `
        -and $nativeFixtureEvidence.overLimit.artifact.actualBytes -eq 125829121 `
        -and $nativeFixtureEvidence.overLimit.artifact.maxBytes -eq 125829120 `
        -and -not $nativeFixtureEvidence.overLimit.artifact.passed) -Name 'native-size-one-byte-over-limit-fails'
Assert-VerificationInvariant -Condition (-not $nativeFixtureEvidence.stale.passed `
        -and -not $nativeFixtureEvidence.stale.artifact.freshThisRun `
        -and -not $nativeFixtureEvidence.stale.artifact.passed) -Name 'native-stale-artifact-fails'
Assert-VerificationInvariant -Condition (-not $nativeFixtureEvidence.missing.passed `
        -and -not $nativeFixtureEvidence.missing.artifact.exists `
        -and -not $nativeFixtureEvidence.missing.artifact.passed) -Name 'native-missing-artifact-fails'

# 仅在隔离子作用域加载 SBOM 法务输入校验器。夹具验证 runner 会拒绝缺失、不完整、过期、文件不存在及超出仓库范围的输入，
# 且不会调用发布生成器或任何 Maven/Cargo/pnpm/Native 命令。
$sbomInputFixtureEvidence = & {
    param(
        [Parameter(Mandatory)][string]$VerificationScript,
        [Parameter(Mandatory)][string]$FixtureRepository
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'verification SBOM input fixture could not parse runner' }
    foreach ($functionName in @('Test-FreshTimestamp', 'Test-FreshSbomInputTimestamp', 'Get-SafePath', 'Get-SbomInputEvidence')) {
        $definition = $ast.Find({
                param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
            }, $true)
        if ($null -eq $definition) { throw "verification SBOM input fixture function is missing: $functionName" }
        Invoke-Expression $definition.Extent.Text
    }

    $repositoryRoot = [System.IO.Path]::GetFullPath($FixtureRepository)
    $freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
    $sbomInputMaxAge = [TimeSpan]::FromHours(24)
    $runStartedAt = [DateTimeOffset]::UtcNow.AddMinutes(-1)
    [System.IO.Directory]::CreateDirectory($repositoryRoot) | Out-Null
    $source = Join-Path $repositoryRoot 'corresponding-source.zip'
    $artifact = Join-Path $repositoryRoot 'ja-bundle.zip'
    $missing = Join-Path $repositoryRoot 'missing-bundle.zip'
    $outside = Join-Path (Split-Path -Parent $repositoryRoot) 'outside-bundle.zip'
    [System.IO.File]::WriteAllBytes($source, [byte[]](1, 2, 3))
    [System.IO.File]::WriteAllBytes($artifact, [byte[]](4, 5, 6))
    [System.IO.File]::WriteAllBytes($outside, [byte[]](7, 8, 9))
    [System.IO.File]::SetLastWriteTimeUtc($source, [DateTime]::UtcNow)
    [System.IO.File]::SetLastWriteTimeUtc($artifact, [DateTime]::UtcNow)
    [System.IO.File]::SetLastWriteTimeUtc($outside, [DateTime]::UtcNow)

    $CorrespondingSourcePath = ''
    $ArtifactPath = @()
    $notProvidedInputs = Get-SbomInputEvidence -Since $runStartedAt

    $CorrespondingSourcePath = $source
    $ArtifactPath = @()
    $partialInputs = Get-SbomInputEvidence -Since $runStartedAt

    $ArtifactPath = @($missing)
    $missingFileInputs = Get-SbomInputEvidence -Since $runStartedAt

    [System.IO.File]::SetLastWriteTimeUtc($artifact, $runStartedAt.UtcDateTime.Subtract($sbomInputMaxAge).AddSeconds(-1))
    $ArtifactPath = @($artifact)
    $staleInputs = Get-SbomInputEvidence -Since $runStartedAt

    [System.IO.File]::SetLastWriteTimeUtc($artifact, [DateTime]::UtcNow)
    $ArtifactPath = @($outside)
    $wrongScopeInputs = Get-SbomInputEvidence -Since $runStartedAt

    $ArtifactPath = @('relative-bundle.zip')
    $relativeInputs = Get-SbomInputEvidence -Since $runStartedAt

    $ArtifactPath = @($artifact)
    $completeInputs = Get-SbomInputEvidence -Since $runStartedAt
    return [ordered]@{
        missing = $notProvidedInputs
        partial = $partialInputs
        missingFile = $missingFileInputs
        stale = $staleInputs
        wrongScope = $wrongScopeInputs
        relative = $relativeInputs
        complete = $completeInputs
        source = $source
        artifact = $artifact
    }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'sbom-input-fixture')

Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.missing.passed `
        -and @($sbomInputFixtureEvidence.missing.blockerCodes) -contains 'SBOM_INPUTS_NOT_PROVIDED') -Name 'sbom-inputs-missing-blocked'
Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.partial.passed `
        -and @($sbomInputFixtureEvidence.partial.blockerCodes) -contains 'SBOM_INPUTS_PARTIAL') -Name 'sbom-inputs-partial-blocked'
Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.missingFile.passed `
        -and @($sbomInputFixtureEvidence.missingFile.blockerCodes) -contains 'SBOM_INPUT_MISSING') -Name 'sbom-input-missing-file-blocked'
Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.stale.passed `
        -and @($sbomInputFixtureEvidence.stale.blockerCodes) -contains 'SBOM_INPUT_STALE') -Name 'sbom-input-stale-blocked'
Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.wrongScope.passed `
        -and @($sbomInputFixtureEvidence.wrongScope.blockerCodes) -contains 'SBOM_INPUT_OUT_OF_SCOPE') -Name 'sbom-input-out-of-scope-blocked'
Assert-VerificationInvariant -Condition (-not $sbomInputFixtureEvidence.relative.passed `
        -and @($sbomInputFixtureEvidence.relative.blockerCodes) -contains 'SBOM_INPUT_NOT_ABSOLUTE') -Name 'sbom-input-relative-blocked'
Assert-VerificationInvariant -Condition ($sbomInputFixtureEvidence.complete.passed `
        -and -not [System.IO.Path]::IsPathRooted([string]$sbomInputFixtureEvidence.complete.inputs.source.path) `
        -and $sbomInputFixtureEvidence.complete.inputs.source.path -eq 'corresponding-source.zip' `
        -and $sbomInputFixtureEvidence.complete.inputs.artifacts[0].sha256) -Name 'sbom-input-complete-fixture-passes'

$completeInventory = @(Get-ListOnlyInventory -EvidenceDirectory (Join-Path $scratchRoot 'list-complete') `
    -CorrespondingSourcePath $sbomInputFixtureEvidence.source -ArtifactPath @($sbomInputFixtureEvidence.artifact))
$completeLicense = $completeInventory | Where-Object name -eq 'sbom-license' | Select-Object -First 1
Assert-VerificationInvariant -Condition (@($completeLicense.arguments) -contains '-CorrespondingSourcePath' `
        -and @($completeLicense.arguments) -contains '-ArtifactPath' `
        -and (@($completeLicense.dependsOn) -contains 'sbom-inputs')) -Name 'sbom-complete-fixture-forwarded-to-generator'

$localInventory = @(Get-ListOnlyInventory -EvidenceDirectory (Join-Path $scratchRoot 'list-local') -AllOptional -LocalVerification)
$localLicense = $localInventory | Where-Object name -eq 'sbom-license' | Select-Object -First 1
$localNative = $localInventory | Where-Object name -eq 'java-native-build' | Select-Object -First 1
Assert-VerificationInvariant -Condition (@($completeLicense.arguments) -contains '-FailOnBlocker' `
        -and @($localLicense.arguments) -notcontains '-FailOnBlocker' `
        -and @($localLicense.dependsOn) -contains 'sbom-inputs' `
        -and @($localNative.dependsOn) -contains 'sbom-license') -Name 'local-purpose-keeps-generation-and-input-gates-with-release-strict-by-default'

# 使用真实临时报告验证本地允许保留发布阻塞，但缺失、过期和被替换的证据都不能成为 Native 前置条件。
$sbomGenerationFixture = & {
    param([string]$VerificationScript, [string]$FixtureRoot)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($VerificationScript, [ref]$tokens, [ref]$errors)
    foreach ($functionName in @('Test-FreshTimestamp', 'Get-SbomLicenseEvidence')) {
        $definition = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }, $true)
        Invoke-Expression $definition.Extent.Text
    }
    $evidenceRoot = $FixtureRoot
    $freshnessFutureSkew = [TimeSpan]::FromMinutes(2)
    $since = [DateTimeOffset]::UtcNow.AddMinutes(-1)
    $directory = Join-Path $evidenceRoot 'sbom'
    [void][System.IO.Directory]::CreateDirectory($directory)
    $bomPath = Join-Path $directory 'ja-app-server-bom.json'
    $reportPath = Join-Path $directory 'dependency-license-report.json'
    [System.IO.File]::WriteAllText($bomPath, '{"bomFormat":"CycloneDX"}')
    $bomHash = (Get-FileHash -LiteralPath $bomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $results = [System.Collections.Generic.List[object]]::new()
    $inputGate = [ordered]@{ name = 'sbom-inputs'; executed = $true; passed = $true }
    $results.Add($inputGate)
    $results.Add([ordered]@{ name = 'sbom-bom-freshness'; executed = $true; passed = $true; details = @{ sha256 = $bomHash } })
    $results.Add([ordered]@{ name = 'sbom-license'; executed = $true; passed = $true })
    $missing = Get-SbomLicenseEvidence -Since $since
    [System.IO.File]::WriteAllText($reportPath, '{"schemaVersion":1,"status":"blocked","blockers":[{"code":"GIT_TREE_DIRTY"},{"code":"LICENSE_ARCHIVE_REVIEW_PENDING"}]}')
    $localReady = Get-SbomLicenseEvidence -Since $since
    [System.IO.File]::SetLastWriteTimeUtc($reportPath, $since.UtcDateTime.AddSeconds(-1))
    $stale = Get-SbomLicenseEvidence -Since $since
    [System.IO.File]::SetLastWriteTimeUtc($reportPath, [DateTime]::UtcNow)
    [System.IO.File]::WriteAllText($bomPath, '{"bomFormat":"changed"}')
    $replaced = Get-SbomLicenseEvidence -Since $since
    [System.IO.File]::WriteAllText($bomPath, '{"bomFormat":"CycloneDX"}')
    [System.IO.File]::WriteAllText($reportPath, 'not JSON')
    $malformed = Get-SbomLicenseEvidence -Since $since
    [System.IO.File]::WriteAllText($reportPath, '{"schemaVersion":99,"status":"complete","blockers":[]}')
    $unknownSchema = Get-SbomLicenseEvidence -Since $since
    $inputGate.passed = $false
    $inputBlocked = Get-SbomLicenseEvidence -Since $since
    return @{ missing = $missing; localReady = $localReady; stale = $stale; replaced = $replaced; malformed = $malformed; unknownSchema = $unknownSchema; inputBlocked = $inputBlocked }
} (Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1') (Join-Path $scratchRoot 'sbom-generation-fixture')
Assert-VerificationInvariant -Condition ($sbomGenerationFixture.localReady.generationPassed `
        -and $sbomGenerationFixture.localReady.status -eq 'failed' `
        -and @($sbomGenerationFixture.localReady.blockerCodes) -contains 'GIT_TREE_DIRTY' `
        -and @($sbomGenerationFixture.localReady.blockerCodes) -contains 'LICENSE_ARCHIVE_REVIEW_PENDING' `
        -and -not $sbomGenerationFixture.localReady.promotedCandidates) -Name 'local-sbom-generation-preserves-release-blockers'
foreach ($rejection in @('missing', 'stale', 'replaced', 'malformed', 'unknownSchema', 'inputBlocked')) {
    Assert-VerificationInvariant -Condition (-not $sbomGenerationFixture[$rejection].generationPassed) -Name "local-sbom-$rejection-rejected"
}

$fakeExecutable = Join-Path $scratchRoot 'fake-ja-desktop.exe'
[System.IO.File]::WriteAllBytes($fakeExecutable, [byte[]](1, 2, 3, 4))
$fakeHash = (Get-FileHash -LiteralPath $fakeExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
$fakeFile = Get-Item -LiteralPath $fakeExecutable
$fakeMtimeNs = [int64](($fakeFile.LastWriteTimeUtc.Ticks - [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc).Ticks) * 100L)
$soakEvidence = Join-Path $scratchRoot 'soak-validate'
$soakOutput = & pwsh -NoProfile -File (Join-Path $repositoryRoot 'scripts\verification\run-runtime-soak.ps1') `
    -EvidenceDirectory $soakEvidence -Executable $fakeExecutable -ExpectedSha256 $fakeHash -ExpectedSizeBytes $fakeFile.Length -ExpectedMtimeNs $fakeMtimeNs `
    -DurationMinutes 120 -ValidateOnly | Out-String
Assert-VerificationInvariant -Condition ($LASTEXITCODE -eq 0) -Name 'soak-validate-exit'
$soakSummary = $soakOutput | ConvertFrom-Json
Assert-VerificationInvariant -Condition ($soakSummary.passed -and $soakSummary.requestedDurationMinutes -eq 120 `
        -and $soakSummary.executableFreshness -eq 'bound-to-caller-identity' `
        -and $soakSummary.expectedIdentityMatches `
        -and $soakSummary.validation.noProcessStarted) -Name 'soak-identity-bound-validation'

$verificationSource = [System.IO.File]::ReadAllText((Join-Path $repositoryRoot 'scripts\verification\run-production-verification.ps1'))
$soakSource = [System.IO.File]::ReadAllText((Join-Path $repositoryRoot 'scripts\verification\run-runtime-soak.ps1'))
Assert-VerificationInvariant -Condition ($verificationSource.Contains('[System.IO.FileMode]::CreateNew') `
        -and $verificationSource.Contains('[DateTime]::UtcNow.Add($freshnessFutureSkew)') `
        -and $verificationSource.Contains("'native-no-fallback-policy' { Get-NativePolicyEvidence }") `
        -and $verificationSource.Contains("'--expected-sha256'") `
        -and $verificationSource.Contains("'--expected-size'") `
        -and $verificationSource.Contains("'--expected-mtime-ns'") `
        -and $verificationSource.Contains('expectedIdentityMatched') `
        -and $verificationSource.Contains('java-25-preflight') `
        -and $verificationSource.Contains('sbom-bom-freshness') `
        -and $verificationSource.Contains("[switch]`$IncludeRuntimeRefresh") `
        -and $verificationSource.Contains("[switch]`$IncludePlan") `
        -and $verificationSource.Contains("[switch]`$IncludeTurnChangeReview") `
        -and $verificationSource.Contains("JA_E2E_RUNTIME_REFRESH_ONLY = '1'") `
        -and $verificationSource.Contains("JA_E2E_REAL_PROVIDER_API_KEY = ''") `
        -and $verificationSource.Contains("JA_E2E_KEEP_TEMP = '0'") `
        -and $verificationSource.Contains("'scripts/e2e/interaction-plan-production.mjs'") `
        -and $verificationSource.Contains("'--soak-minutes', [string]`$PlanSoakDurationMinutes") `
        -and $verificationSource.Contains("JA_E2E_REAL_PROVIDER = '0'") `
        -and $verificationSource.Contains("'interaction-plan-windows-webview2'") `
        -and $verificationSource.Contains("'runtime-refresh-windows-webview2'") `
        -and $verificationSource.Contains("'turn-change-review-windows-webview2'") `
        -and $verificationSource.Contains('blocked-exit-code-2')) -Name 'freshness-identity-source-contract'
Assert-VerificationInvariant -Condition ($soakSource.Contains('ReadToEndAsync()') `
        -and $soakSource.Contains("'bound-to-caller-identity'")) -Name 'soak-drain-and-identity-source-contract'

[ordered]@{
    passed = $true
    totalGates = $all.Count
    defaultRequestedGates = @($base | Where-Object requested).Count
    allRequestedGates = @($all | Where-Object requested).Count
    nativeBuildCount = $nativeBuilds.Count
    nativeBuildCommand = $nativeBuild.command
    nativeBuildCleanBeforePackage = $nativeCleanIndex -ge 0 -and $nativePackageIndex -gt $nativeCleanIndex
    nativeNoFallbackPolicyInternal = $nativePolicy.kind -eq 'internal'
    staleEvidenceBlocked = $true
    nativeExecutableMaxBytes = $nativeFixtureEvidence.atLimit.artifact.maxBytes
    nativeBoundaryPassed = $nativeFixtureEvidence.atLimit.passed
    nativeOversizeBlocked = -not $nativeFixtureEvidence.overLimit.passed
    nativeStaleBlocked = -not $nativeFixtureEvidence.stale.passed
    nativeMissingBlocked = -not $nativeFixtureEvidence.missing.passed
    sbomMissingBlocked = -not $sbomInputFixtureEvidence.missing.passed
    sbomPartialBlocked = -not $sbomInputFixtureEvidence.partial.passed
    sbomStaleBlocked = -not $sbomInputFixtureEvidence.stale.passed
    sbomWrongScopeBlocked = -not $sbomInputFixtureEvidence.wrongScope.passed
    sbomCompleteForwarded = $true
    localSbomGenerationPreservesReleaseBlockers = $sbomGenerationFixture.localReady.generationPassed -and $sbomGenerationFixture.localReady.status -eq 'failed'
    localSbomInvalidEvidenceRejected = -not ($sbomGenerationFixture.missing.generationPassed -or $sbomGenerationFixture.stale.generationPassed -or $sbomGenerationFixture.replaced.generationPassed -or $sbomGenerationFixture.malformed.generationPassed -or $sbomGenerationFixture.unknownSchema.generationPassed -or $sbomGenerationFixture.inputBlocked.generationPassed)
    secretPersisted = $false
    soakDurationMinutes = $soakSummary.requestedDurationMinutes
    soakExecutableFreshness = $soakSummary.executableFreshness
    evidenceDirectory = [System.IO.Path]::GetRelativePath($repositoryRoot, $scratchRoot).Replace('\', '/')
} | ConvertTo-Json -Depth 6
