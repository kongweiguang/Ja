# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\lib\diagnostics.ps1')

function Assert-Condition {
    <# 把供应链脚本契约失败收敛成稳定断言，避免测试只依赖外部命令退出码。 #>
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Condition) {
        throw "SBOM_SCRIPT_TEST_FAILED: $Name"
    }
}

function Write-Utf8File {
    <# 固定无 BOM UTF-8 与 LF，使 fixture 哈希不受 Windows 默认编码影响。 #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, ($Content -replace "`r`n", "`n"), [Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    <# 计算 fixture 的精确 bytes 身份，复用生产 manifest 的小写 SHA-256 契约。 #>
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-CycloneDxEvidenceHash {
    <# 复现生产规范化哈希，锁定“时间戳变化不漂移、依赖内容变化必漂移”的回归契约。 #>
    param([Parameter(Mandatory = $true)][string]$Path)

    $bom = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($bom.PSObject.Properties['serialNumber']) {
        $bom.PSObject.Properties.Remove('serialNumber')
    }
    if ($bom.metadata -and $bom.metadata.PSObject.Properties['timestamp']) {
        $bom.metadata.PSObject.Properties.Remove('timestamp')
    }
    if ($bom.components) {
        $bom.components = @($bom.components | Sort-Object { [string]$_.'bom-ref' })
    }
    if ($bom.dependencies) {
        foreach ($dependency in @($bom.dependencies)) {
            if ($dependency.dependsOn) {
                $dependency.dependsOn = @($dependency.dependsOn | Sort-Object)
            }
        }
        $bom.dependencies = @($bom.dependencies | Sort-Object { [string]$_.ref })
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($bom | ConvertTo-Json -Depth 100 -Compress))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ($algorithm.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $algorithm.Dispose()
    }
}

function Write-JsonFile {
    <# 生成机器 fixture；测试只关心字段与哈希，不依赖 PowerShell 对象展示格式。 #>
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    Write-Utf8File -Path $Path -Content (($Value | ConvertTo-Json -Depth 20) + "`n")
}

function Invoke-MavenBomAuditRegression {
    <# 只装配真实审计函数，验证可选元数据缺省仍被计为缺失，避免测试依赖外部包工具或补造许可证来通过。 #>
    param([Parameter(Mandatory = $true)][string]$FixtureRoot)

    $generatorPath = Join-Path $PSScriptRoot 'generate.ps1'
    $tokens = $null
    $parseErrors = $null
    $generatorAst = [Management.Automation.Language.Parser]::ParseFile($generatorPath, [ref]$tokens, [ref]$parseErrors)
    Assert-Condition -Condition (@($parseErrors).Count -eq 0) -Name 'generator-parses'
    $auditFunctions = @($generatorAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -in @('Normalize-MavenBom', 'Get-MavenBomAudit')
    }, $false))
    Assert-Condition -Condition ($auditFunctions.Count -eq 2) -Name 'real-maven-audit-functions-present'
    foreach ($definition in $auditFunctions) { . ([ScriptBlock]::Create($definition.Extent.Text)) }

    $auditPath = Join-Path $FixtureRoot 'audit/optional-components.json'
    $bom = [PSCustomObject][ordered]@{
        bomFormat = 'CycloneDX'
        specVersion = '1.6'
        metadata = [PSCustomObject]@{
            component = [PSCustomObject]@{ licenses = @([PSCustomObject]@{ license = [PSCustomObject]@{ id = 'GPL-3.0-or-later' } }) }
        }
        components = @(
            [PSCustomObject]@{
                'bom-ref' = 'complete'
                licenses = @([PSCustomObject]@{ license = [PSCustomObject]@{ id = 'MIT' } })
                hashes = @([PSCustomObject]@{ alg = 'SHA-256'; content = ('a' * 64) })
                externalReferences = @([PSCustomObject]@{ type = 'website'; url = 'https://example.invalid/fixture' })
            },
            [PSCustomObject]@{ 'bom-ref' = 'omitted' },
            [PSCustomObject]@{ 'bom-ref' = 'empty'; licenses = @(); hashes = @(); externalReferences = @() },
            [PSCustomObject]@{
                'bom-ref' = 'sha1-only'
                licenses = @([PSCustomObject]@{ license = [PSCustomObject]@{ id = 'MIT' } })
                hashes = @([PSCustomObject]@{ alg = 'SHA-1'; content = ('b' * 40) })
                externalReferences = @()
            }
        )
        dependencies = @([PSCustomObject]@{ ref = 'omitted' })
    }
    Write-JsonFile -Path $auditPath -Value $bom
    $audit = Get-MavenBomAudit -Path $auditPath
    Assert-Condition -Condition ($audit.componentCount -eq 4) -Name 'optional-component-count'
    Assert-Condition -Condition ($audit.missingLicenseCount -eq 2) -Name 'omitted-and-empty-licenses-remain-missing'
    Assert-Condition -Condition ($audit.missingSha256Count -eq 3) -Name 'omitted-empty-and-non-sha256-hashes-remain-missing'
    Assert-Condition -Condition ($audit.missingExternalReferenceCount -eq 3) -Name 'omitted-and-empty-references-remain-missing'
    Assert-Condition -Condition $audit.rootLicenseMatchesJa -Name 'root-gpl-id-remains-supported'
    $omitted = @($audit.normalizedBom.components | Where-Object { $_.'bom-ref' -eq 'omitted' })[0]
    Assert-Condition -Condition (-not $omitted.PSObject.Properties['licenses'] -and -not $omitted.PSObject.Properties['hashes'] -and
        -not $omitted.PSObject.Properties['externalReferences']) -Name 'normalization-does-not-fabricate-metadata'

    $minimalPath = Join-Path $FixtureRoot 'audit/minimal-bom.json'
    Write-JsonFile -Path $minimalPath -Value ([PSCustomObject]@{ bomFormat = 'CycloneDX'; specVersion = '1.6' })
    $minimalAudit = Get-MavenBomAudit -Path $minimalPath
    Assert-Condition -Condition ($minimalAudit.componentCount -eq 0 -and -not $minimalAudit.rootLicenseMatchesJa) -Name 'missing-root-metadata-remains-unapproved'
    $bom.metadata.component.licenses = @([PSCustomObject]@{ license = [PSCustomObject]@{ name = 'GNU General Public License version 3.0 or later' } })
    Write-JsonFile -Path $auditPath -Value $bom
    Assert-Condition -Condition (Get-MavenBomAudit -Path $auditPath).rootLicenseMatchesJa -Name 'root-gpl-name-remains-supported'
}

function Invoke-NativeJsonRegression {
    <# 使用真实原生子进程覆盖双流排空和非 UTF-8 宿主，并验证异常恢复，避免假 JSON fixture 掩盖 Windows 解码与死锁边界。 #>
    param([Parameter(Mandatory = $true)][string]$FixtureRoot)
    $root = [IO.Path]::GetFullPath($FixtureRoot)

    $generatorAst = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'generate.ps1'), [ref]$null, [ref]$null)
    foreach ($name in @('Invoke-ExternalCommand', 'Convert-JsonCommandOutput')) {
        $definition = $generatorAst.Find({
            param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        }, $false)
        Assert-Condition -Condition ($null -ne $definition) -Name ('native-json-function-present-' + $name)
        . ([ScriptBlock]::Create($definition.Extent.Text))
    }
    $nativeFixture = Join-Path $FixtureRoot 'native json fixture.ps1'
    Write-Utf8File -Path $nativeFixture -Content @'
# @author kongweiguang
param([string]$Label, [int]$ExitCode = 0, [string]$Mode = 'object')
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$json = @{ result = 'ok'; label = $Label; unicode = '中文' } | ConvertTo-Json -Compress
[Console]::Error.WriteLine('{"diagnostic":"fixture stderr"}')
switch ($Mode) {
    'prefix' { [Console]::Out.WriteLine('unexpected stdout prefix'); [Console]::Out.WriteLine($json) }
    'suffix' { [Console]::Out.WriteLine($json); [Console]::Out.WriteLine('unexpected stdout suffix') }
    'multiple' { [Console]::Out.WriteLine($json); [Console]::Out.WriteLine($json) }
    'array' { [Console]::Out.WriteLine('[]') }
    'buffered' {
        for ($index = 0; $index -lt 128; $index++) { [Console]::Error.WriteLine(('fixture' * 600)) }
        [Console]::Out.WriteLine((@{ result = 'ok'; payload = ('中文' * 131072) } | ConvertTo-Json -Compress))
    }
    default { [Console]::Out.WriteLine($json) }
}
exit $ExitCode
'@
    $label = '路径空格 & 字符 $()'
    $arguments = @('-NoProfile', '-File', $nativeFixture, '-Label', $label)
    $priorConsoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.Encoding]::GetEncoding(936)
        $success = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments $arguments
        Assert-Condition -Condition ([Console]::OutputEncoding.CodePage -eq 936) -Name 'native-json-caller-encoding-restored'
    } finally {
        [Console]::OutputEncoding = $priorConsoleEncoding
    }
    Assert-Condition -Condition ($success.ExitCode -eq 0) -Name 'native-json-zero-exit'
    $parsed = Convert-JsonCommandOutput -Lines $success.Output
    Assert-Condition -Condition ($parsed.result -eq 'ok' -and $parsed.label -eq $label -and $parsed.unicode -eq '中文') -Name 'native-json-stdout-and-literal-arguments'
    Assert-Condition -Condition (($success.StandardError -join "`n").Contains('{"diagnostic":"fixture stderr"}')) -Name 'native-json-stderr-separated'
    Assert-Condition -Condition (-not (($success.Output -join "`n").Contains('diagnostic'))) -Name 'native-json-stderr-never-in-stdout'

    $buffered = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @($arguments + @('-Mode', 'buffered'))
    $bufferedJson = Convert-JsonCommandOutput -Lines $buffered.Output
    Assert-Condition -Condition ($buffered.ExitCode -eq 0 -and $bufferedJson.payload.Length -eq 262144 -and $buffered.StandardError.Count -ge 128) -Name 'native-json-both-pipes-drained-beyond-buffer'

    $priorConsoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.Encoding]::GetEncoding(936)
        $nativeFailure = $false
        try { $null = Invoke-ExternalCommand -FilePath ('ja-sbom-missing-native-' + [guid]::NewGuid().ToString('N')) -Arguments @('--version') } catch { $nativeFailure = $true }
        Assert-Condition -Condition ($nativeFailure -and [Console]::OutputEncoding.CodePage -eq 936) -Name 'native-json-exception-restores-caller-encoding'
    } finally {
        [Console]::OutputEncoding = $priorConsoleEncoding
    }

    $failed = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @($arguments + @('-ExitCode', '7'))
    Assert-Condition -Condition ($failed.ExitCode -eq 7) -Name 'native-json-nonzero-exit-preserved'
    foreach ($mode in @('prefix', 'suffix', 'multiple', 'array')) {
        $polluted = Invoke-ExternalCommand -FilePath 'pwsh' -Arguments @($arguments + @('-Mode', $mode))
        Assert-Condition -Condition ($polluted.ExitCode -eq 0) -Name ('native-json-pollution-fixture-exit-' + $mode)
        $rejected = $false
        try { $null = Convert-JsonCommandOutput -Lines $polluted.Output } catch { $rejected = $true }
        Assert-Condition -Condition $rejected -Name ('native-json-complete-stdout-rejected-' + $mode)
    }
}

# 两个生产 runner 共用真实子进程夹具，验证恢复后下一命令的状态及失败诊断，避免测试自己实现一份环境恢复器。
function Invoke-NativeEnvironmentRegression {
    param([Parameter(Mandatory)][string]$FixtureRoot)
    $root = [IO.Path]::GetFullPath($FixtureRoot)

    $generator = Join-Path $PSScriptRoot 'generate.ps1'
    $collector = Join-Path $PSScriptRoot 'collect-license-candidates.ps1'
    $baseline = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\..\rust-toolchain.toml') -Raw
    $channel = [regex]::Match($baseline, '(?m)^channel\s*=\s*"([^"]+)"').Groups[1].Value
    foreach ($path in @($generator, $collector)) {
        $ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$null)
        $parameter = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'CargoToolchain' }
        Assert-Condition -Condition ($parameter.DefaultValue.SafeGetValue() -eq $channel) -Name ('cargo-default-matches-repository-' + [IO.Path]::GetFileName($path))
        foreach ($name in @('Invoke-ExternalCommand', 'Convert-JsonCommandOutput', 'Invoke-JsonCommand')) {
            $definition = $ast.Find({
                param($node)
                $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
            }, $false)
            if ($null -ne $definition) { . ([ScriptBlock]::Create($definition.Extent.Text)) }
        }
    }
    $probe = Join-Path $FixtureRoot 'environment probe.ps1'
    Write-Utf8File -Path $probe -Content @'
# @author kongweiguang
param([string]$Name, [int]$ExitCode = 0)
$value = [Environment]::GetEnvironmentVariable($Name, 'Process')
[Console]::Out.WriteLine((@{ exists = $null -ne $value; value = $value } | ConvertTo-Json -Compress))
if ($ExitCode -ne 0) { [Console]::Error.WriteLine('fixture native diagnostic authorization=fixture-only-confidential-value') }
exit $ExitCode
'@
    $variable = 'JA_SBOM_FIXTURE_VALUE'
    $original = [Environment]::GetEnvironmentVariable($variable, 'Process')
    $originalErrorWriter = [Console]::Error
    try {
        foreach ($runner in @('generator', 'collector')) {
            foreach ($state in @('missing', 'empty', 'value')) {
                foreach ($mode in @('success', 'nonzero', 'unavailable')) {
                    if ($state -eq 'missing') {
                        [Environment]::SetEnvironmentVariable($variable, [System.Management.Automation.Language.NullString]::Value, 'Process')
                    } else {
                        [Environment]::SetEnvironmentVariable($variable, $(if ($state -eq 'empty') { '' } else { 'original-fixture-value' }), 'Process')
                    }
                    $expected = [Environment]::GetEnvironmentVariable($variable, 'Process')
                    $label = $runner + '-' + $state + '-' + $mode
                    $command = if ($mode -eq 'unavailable') { 'ja-sbom-missing-native-' + [guid]::NewGuid().ToString('N') } else { 'pwsh' }
                    $arguments = @('-NoProfile', '-File', $probe, '-Name', $variable, '-ExitCode', $(if ($mode -eq 'nonzero') { '7' } else { '0' }))
                    $writer = [IO.StringWriter]::new()
                    [Console]::SetError($writer)
                    $failed = $false
                    $result = $null
                    try {
                        if ($runner -eq 'generator') {
                            $result = Invoke-ExternalCommand -FilePath $command -Arguments $arguments -Environment @{ JA_SBOM_FIXTURE_VALUE = 'temporary-fixture-value' }
                        } else {
                            $result = Invoke-JsonCommand -FilePath $command -Arguments $arguments -Environment @{ JA_SBOM_FIXTURE_VALUE = 'temporary-fixture-value' }
                        }
                    } catch { $failed = $true } finally { [Console]::SetError($originalErrorWriter) }
                    $diagnostic = $writer.ToString()
                    $writer.Dispose()
                    if ($mode -eq 'success') {
                        $during = if ($runner -eq 'generator') { Convert-JsonCommandOutput -Lines $result.Output } else { $result }
                        Assert-Condition -Condition ($during.exists -and $during.value -eq 'temporary-fixture-value' -and -not $failed) -Name ($label + '-child-sees-override')
                    } elseif ($mode -eq 'nonzero') {
                        Assert-Condition -Condition ($(if ($runner -eq 'generator') { -not $failed -and $result.ExitCode -eq 7 } else { $failed })) -Name ($label + '-real-failure')
                        Assert-Condition -Condition ($diagnostic.Contains('fixture native diagnostic') -and $diagnostic.Contains('<redacted>') -and -not $diagnostic.Contains('fixture-only-confidential-value')) -Name ($label + '-safe-stderr-preserved')
                    } else {
                        Assert-Condition -Condition $failed -Name ($label + '-start-failure')
                    }
                    $next = (& pwsh -NoProfile -File $probe -Name $variable | Out-String) | ConvertFrom-Json
                    Assert-Condition -Condition ($LASTEXITCODE -eq 0 -and $next.exists -eq ($null -ne $expected) -and $next.value -ceq $expected) -Name ($label + '-next-child-sees-original')
                }
            }
        }
    } finally {
        [Console]::SetError($originalErrorWriter)
        if ($null -eq $original) {
            [Environment]::SetEnvironmentVariable($variable, [System.Management.Automation.Language.NullString]::Value, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($variable, $original, 'Process')
        }
    }
}

$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryBase ('ja-sbom-script-test-' + [guid]::NewGuid().ToString('N'))
$promoteScript = Join-Path $PSScriptRoot 'promote-license-candidates.ps1'

try {
    [IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
    Invoke-MavenBomAuditRegression -FixtureRoot $fixtureRoot
    Invoke-NativeJsonRegression -FixtureRoot $fixtureRoot
    Invoke-NativeEnvironmentRegression -FixtureRoot $fixtureRoot
    $inputByRole = [ordered]@{
        'node-manifest' = 'package.json'
        'node-lock' = 'pnpm-lock.yaml'
        'cargo-workspace-manifest' = 'Cargo.toml'
        'cargo-package-manifest' = 'apps/desktop/src-tauri/Cargo.toml'
        'cargo-lock' = 'Cargo.lock'
        'maven-parent-manifest' = 'ja-agent/pom.xml'
        'maven-core-manifest' = 'ja-agent/ja-agent-core/pom.xml'
        'maven-store-local-manifest' = 'ja-agent/extensions/ja-agent-store-local/pom.xml'
        'maven-store-pg-manifest' = 'ja-agent/extensions/ja-agent-store-pg/pom.xml'
        'maven-skills-local-manifest' = 'ja-agent/extensions/ja-agent-skills-local/pom.xml'
        'maven-skills-pg-manifest' = 'ja-agent/extensions/ja-agent-skills-pg/pom.xml'
        'maven-example-manifest' = 'ja-agent/example/pom.xml'
        'maven-example-spring-boot-manifest' = 'ja-agent/example/ja-agent-example-springboot/pom.xml'
        'maven-app-server-manifest' = 'ja-agent/ja-app-server/pom.xml'
        'maven-bom' = 'ja-agent/target/ja-app-server-bom.json'
    }
    foreach ($entry in $inputByRole.GetEnumerator()) {
        $path = Join-Path $fixtureRoot $entry.Value
        if ([string]$entry.Key -eq 'maven-bom') {
            Write-JsonFile -Path $path -Value ([PSCustomObject][ordered]@{
                bomFormat = 'CycloneDX'
                specVersion = '1.6'
                serialNumber = 'urn:uuid:fixture'
                metadata = [PSCustomObject][ordered]@{ timestamp = '2026-01-01T00:00:00Z' }
                components = @([PSCustomObject][ordered]@{ 'bom-ref' = 'pkg:maven/example/fixture@1.0.0'; version = '1.0.0' })
                dependencies = @([PSCustomObject][ordered]@{ ref = 'pkg:maven/example/fixture@1.0.0'; dependsOn = @() })
            })
        } else {
            Write-Utf8File -Path $path -Content ("fixture:{0}`n" -f $entry.Key)
        }
    }

    $candidateRoot = Join-Path $fixtureRoot 'candidate'
    $licensePath = Join-Path $candidateRoot 'text/license.txt'
    Write-Utf8File -Path $licensePath -Content "Permission is hereby granted for fixture use.`n"
    $licenseHash = Get-Sha256 -Path $licensePath
    $hashAddressedLicensePath = Join-Path $candidateRoot ("text/$licenseHash.txt")
    Move-Item -LiteralPath $licensePath -Destination $hashAddressedLicensePath
    $licensePath = $hashAddressedLicensePath
    $inputs = @($inputByRole.GetEnumerator() | ForEach-Object {
        $path = Join-Path $fixtureRoot $_.Value
        $hashMode = if ([string]$_.Key -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        $hash = if ($hashMode -eq 'raw-bytes') { Get-Sha256 -Path $path } else { Get-CycloneDxEvidenceHash -Path $path }
        [PSCustomObject][ordered]@{ role = [string]$_.Key; path = ([string]$_.Value).Replace('\', '/'); hashMode = $hashMode; sha256 = $hash }
    })
    $candidate = [PSCustomObject][ordered]@{
        schemaVersion = 4
        status = 'candidate-review-pending'
        inputs = $inputs
        mappings = @([PSCustomObject][ordered]@{
            ecosystem = 'npm'
            name = 'fixture-package'
            version = '1.0.0'
            declaredLicense = 'MIT'
            repository = 'https://example.invalid/fixture'
            homepage = ''
            sourceFile = 'LICENSE'
            archiveFile = "text/$licenseHash.txt"
            sha256 = $licenseHash
        })
        missing = @()
        summary = [PSCustomObject][ordered]@{
            uniqueTextFiles = 1
            mappingCount = 1
            missingTextCount = 0
        }
    }
    Write-JsonFile -Value $candidate -Path (Join-Path $candidateRoot 'manifest.json')

    Write-Utf8File -Path (Join-Path $fixtureRoot 'Cargo.lock') -Content "fixture:changed-cargo-lock`n"
    $staleOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ConfirmSourceReview 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -ne 0 -and ($staleOutput -join "`n").Contains('candidate input changed after collection: cargo-lock')) -Name 'stale-input-rejected'
    Assert-Condition -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved'))) -Name 'stale-input-no-archive'

    Write-Utf8File -Path (Join-Path $fixtureRoot 'Cargo.lock') -Content "fixture:cargo-lock`n"
    $candidate.inputs = @($inputByRole.GetEnumerator() | ForEach-Object {
        $path = Join-Path $fixtureRoot $_.Value
        $hashMode = if ([string]$_.Key -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        $hash = if ($hashMode -eq 'raw-bytes') { Get-Sha256 -Path $path } else { Get-CycloneDxEvidenceHash -Path $path }
        [PSCustomObject][ordered]@{ role = [string]$_.Key; path = ([string]$_.Value).Replace('\', '/'); hashMode = $hashMode; sha256 = $hash }
    })
    Write-JsonFile -Value $candidate -Path (Join-Path $candidateRoot 'manifest.json')

    $bomPath = Join-Path $fixtureRoot $inputByRole['maven-bom']
    $bom = Get-Content -Raw -LiteralPath $bomPath | ConvertFrom-Json
    $bom.metadata.timestamp = '2026-01-02T00:00:00Z'
    Write-JsonFile -Value $bom -Path $bomPath
    $validationOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ValidateOnly 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -eq 0 -and ($validationOutput -join "`n").Contains('status=candidate-validated')) -Name ('candidate-validation-succeeded: ' + ($validationOutput -join ' '))
    $bom.components[0].version = '2.0.0'
    Write-JsonFile -Value $bom -Path $bomPath
    $bomDriftOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ValidateOnly 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -ne 0 -and ($bomDriftOutput -join "`n").Contains('candidate input changed after collection: maven-bom')) -Name 'bom-content-drift-rejected'
    $bom.components[0].version = '1.0.0'
    Write-JsonFile -Value $bom -Path $bomPath
    Assert-Condition -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved'))) -Name 'validation-does-not-promote'
    $promotionOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ConfirmSourceReview 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -eq 0) -Name ('pending-promotion-succeeded: ' + ($promotionOutput -join ' '))
    $archive = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved/manifest.json') | ConvertFrom-Json
    Assert-Condition -Condition ([int]$archive.schemaVersion -eq 4) -Name 'archive-schema-4'
    Assert-Condition -Condition ([string]$archive.status -eq 'source-verified-pending-legal-review') -Name 'promotion-does-not-approve'
    Assert-Condition -Condition (@($archive.inputs).Count -eq 15) -Name 'role-bound-inputs-preserved'
    $archiveText = Join-Path (Join-Path $fixtureRoot 'LICENSES/approved/text') ($candidate.mappings[0].sha256 + '.txt')
    Assert-Condition -Condition ((Get-Sha256 -Path $archiveText) -eq $candidate.mappings[0].sha256) -Name 'archive-bytes-match-hash'

    Write-Output 'SBOM_SCRIPT_TEST_OK stale=1 bomTimestampIgnored=1 bomDrift=1 pendingPromotion=1 inputs=15 mavenOptional=1 mavenMissingRoot=1 mavenNoFabrication=1 nativeStreams=1 nativeUtf8=1 nativeDrain=1 nativeRestore=1 nativeExit=1 strictStdout=1'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $expectedPrefix = $temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedFixture.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedFixture).StartsWith('ja-sbom-script-test-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
